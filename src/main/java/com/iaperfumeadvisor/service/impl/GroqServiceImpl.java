package com.iaperfumeadvisor.service.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.iaperfumeadvisor.ai.PreferenceCriteria;
import com.iaperfumeadvisor.dto.request.client.ChatHistoryItem;
import com.iaperfumeadvisor.enums.GenderType;
import com.iaperfumeadvisor.enums.PerfumeCategory;
import com.iaperfumeadvisor.exception.BusinessException;
import com.iaperfumeadvisor.service.GroqService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Cliente HTTP crudo (sin SDK) contra la API de Groq, con formato de mensajes estilo OpenAI.
// Elige entre el modelo con busqueda agentica y el modelo de reserva de texto plano segun
// "allowSearch" (ver GroqService/ChatServiceImpl para el motivo).
@Service
public class GroqServiceImpl implements GroqService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${groq.api-key:}")
    private String apiKey;

    @Value("${groq.model:qwen/qwen3.8-27b}")
    private String model;

    // Modelo de texto plano (sin busqueda agentica) para cuando ademas hay que razonar contra
    // varios productos del catalogo: el modelo principal (antes groq/compound) ahi a veces disparaba busquedas internas
    // (una por cada perfume que compara) y se pasa del limite de tokens por pedido del plan gratis.
    @Value("${groq.fallback-model:openai/gpt-oss-120b}")
    private String fallbackModel;

    @Value("${groq.api-url:https://api.groq.com/openai/v1}")
    private String apiBaseUrl;

    // Modelo mas chico que el de fallback (mismo estilo "reasoning" de OpenAI), sin busqueda
    // agentica: alcanza y sobra para la tarea de extraccion (devolver un json cortito), y no tiene
    // sentido pagar la latencia/costo del modelo grande para esto.
    @Value("${groq.extraction-model:openai/gpt-oss-20b}")
    private String extractionModel;

    @Override
    public String generateChatResponse(String systemInstruction, List<ChatHistoryItem> history, String currentMessage, boolean allowSearch) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException("Groq API key no configurada (groq.api-key)");
        }

        try {
            // max_tokens acota la respuesta a algo del largo de un mensaje de chat real: ademas
            // de leerse mejor, un modelo que genera menos tokens responde bastante mas rapido.
            // El modelo de reserva (sin busqueda) es un modelo "de razonamiento": gasta buena
            // parte del presupuesto pensando para si mismo antes de escribir la respuesta final
            // (queda en un campo "reasoning" aparte), asi que necesita bastante mas margen o se
            // queda sin tokens para la respuesta real.
            Map<String, Object> requestBody = Map.of(
                    "model", allowSearch ? model : fallbackModel,
                    "messages", buildMessages(systemInstruction, history, currentMessage),
                    "max_tokens", allowSearch ? 220 : 700
            );

            String url = apiBaseUrl + "/chat/completions";

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new BusinessException("Groq respondio con error " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String text = extractText(root);
            if (text.isBlank()) {
                throw new BusinessException("Groq devolvio una respuesta vacia");
            }
            return text;

        } catch (IOException ex) {
            throw new BusinessException("Error de comunicacion con Groq", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Llamada a Groq interrumpida", ex);
        }
    }

    // Groq usa el formato estilo OpenAI: un array de mensajes con roles "system"/"user"/"assistant".
    private List<Map<String, Object>> buildMessages(String systemInstruction, List<ChatHistoryItem> history, String currentMessage) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemInstruction));
        if (history != null) {
            for (ChatHistoryItem turn : history) {
                if (turn.getMessage() == null || turn.getMessage().isBlank()) {
                    continue;
                }
                String role = "user".equalsIgnoreCase(turn.getRole()) ? "user" : "assistant";
                messages.add(Map.of("role", role, "content", turn.getMessage()));
            }
        }
        messages.add(Map.of("role", "user", "content", currentMessage));
        return messages;
    }

    private String extractText(JsonNode root) {
        return root.path("choices").get(0).path("message").path("content").asText("").trim();
    }

    @Override
    public PreferenceCriteria extractCriteria(String message, List<String> catalogPerfumeNames) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException("Groq API key no configurada (groq.api-key)");
        }

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", extractionModel,
                    "messages", List.of(
                            Map.of("role", "system", "content", buildExtractionPrompt(catalogPerfumeNames)),
                            Map.of("role", "user", "content", message)
                    ),
                    // reasoning_effort "low": es un modelo "de razonamiento" (mismo estilo que el de
                    // reserva del chat) y esto es solo una extraccion cortita, no hace falta que piense
                    // mucho. Sin esto, en mensajes mas complejos (ej. "parecido a tal perfume de otra
                    // marca") se puede quedar sin presupuesto de tokens pensando y nunca llega a escribir
                    // el json final (error json_validate_failed / "max completion tokens reached").
                    "reasoning_effort", "low",
                    // temperature 0: esto es una extraccion de datos estructurados, no texto creativo.
                    // Sin esto el modelo puede variar la respuesta para el mismo mensaje exacto (ej.
                    // catalogar un perfume conocido distinto cada vez), lo cual es confuso e inconsistente.
                    "temperature", 0,
                    "max_tokens", 700,
                    "response_format", Map.of("type", "json_object")
            );

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new BusinessException("Groq (extraccion de criterios) respondio con error "
                        + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String text = extractText(root);
            if (text.isBlank()) {
                throw new BusinessException("Groq (extraccion de criterios) devolvio una respuesta vacia");
            }

            return parseCriteria(text);

        } catch (IOException ex) {
            throw new BusinessException("Error de comunicacion con Groq (extraccion de criterios)", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Llamada a Groq (extraccion de criterios) interrumpida", ex);
        }
    }

    private String buildExtractionPrompt(List<String> catalogPerfumeNames) {
        String namesList = (catalogPerfumeNames == null || catalogPerfumeNames.isEmpty())
                ? "(no hay productos con stock ahora mismo)"
                : String.join(", ", catalogPerfumeNames);

        return "Sos un extractor de intencion de busqueda para el chat de venta de una perfumeria. "
                + "El mensaje del cliente puede tener errores de tipeo, ser muy corto o muy largo, y estar en "
                + "cualquier forma de escribir lo mismo: interpretalo igual. Devolve SOLO un objeto json (nada "
                + "de texto ni explicacion antes o despues) con estos campos:\n"
                + "- category: uno de FLORAL, FRUITY, ORIENTAL, WOODY, FRESH, CHYPRE, AROMATIC, CITRUS, el que "
                + "mejor represente la familia olfativa de lo que pide (ej: vainilla/dulce/gourmand/canela va en "
                + "ORIENTAL, cuero/madera/almizcle en WOODY, flores en FLORAL, citricos en CITRUS), o null si no "
                + "se puede inferir ninguna. Si el cliente nombra un perfume o marca puntual que NO es nuestra "
                + "(ej: \"parecido al Good Girl de Carolina Herrera\", \"alternativa al Sauvage\"), usa tu propio "
                + "conocimiento de ese perfume para inferir su familia olfativa real, no lo dejes en null solo "
                + "porque el cliente no describio las notas el mismo.\n"
                + "- genderType: uno de MALE, FEMALE, UNISEX, o null si no lo menciono. Mismo criterio que "
                + "category: si nombra un perfume de otra marca, infiere el genero tipico de ese perfume con tu "
                + "propio conocimiento.\n"
                + "- keywords: lista corta (maximo 6) de palabras sueltas en español, en minuscula y sin tildes, "
                + "con las notas/aromas/preferencias puntuales que menciono (ej: [\"vainilla\", \"dulce\"]), "
                + "corrigiendo errores de tipeo evidentes. Si el cliente nombro (aunque con errores de tipeo) "
                + "alguno de estos productos del catalogo: " + namesList + "; incluí cada palabra de su nombre "
                + "correcto por separado en la lista.\n"
                + "- productIntent: true si el mensaje pide perfumes, recomendaciones, o menciona/pregunta por UN "
                + "perfume o marca puntual (propia o de otra marca) de cualquier forma, incluyendo \"parecido a\", "
                + "\"alternativa a\", \"inspirado en\", \"dupe de\", \"tenes algo como...\", o directamente el "
                + "nombre de un perfume. false si es un saludo, agradecimiento, o una pregunta sobre el negocio en "
                + "si (envios, formas de pago, horarios, ubicacion) que NO menciona ningun perfume/marca/categoria "
                + "ni pide una recomendacion.\n"
                + "- needsClarification: true solo si pidio perfumes pero no dio NINGUNA pista concreta (ni "
                + "categoria, ni genero, ni nota, ni nombre de producto), y conviene preguntarle algo antes de "
                + "recomendar cualquier cosa.\n"
                + "- referencesSpecificPerfume: true si pregunta por UN perfume puntual (propio o de otra "
                + "marca), por ejemplo con \"parecido a\", \"inspirado en\", \"alternativa a\", \"dupe de\", o "
                + "nombrando directamente un perfume.\n"
                + "Responde solo con ese json.";
    }

    private PreferenceCriteria parseCriteria(String jsonText) {
        try {
            JsonNode node = objectMapper.readTree(jsonText);

            List<String> keywords = new ArrayList<>();
            if (node.path("keywords").isArray()) {
                for (JsonNode keywordNode : node.path("keywords")) {
                    for (String token : normalize(keywordNode.asText("")).split("[^a-z0-9]+")) {
                        if (!token.isBlank()) {
                            keywords.add(token);
                        }
                    }
                }
            }

            return PreferenceCriteria.builder()
                    .category(parseEnum(PerfumeCategory.class, node.path("category").asText(null)))
                    .genderType(parseEnum(GenderType.class, node.path("genderType").asText(null)))
                    .keywords(keywords)
                    .productIntent(node.path("productIntent").asBoolean(false))
                    .needsClarification(node.path("needsClarification").asBoolean(false))
                    .referencesSpecificPerfume(node.path("referencesSpecificPerfume").asBoolean(false))
                    .build();

        } catch (JacksonException ex) {
            throw new BusinessException("No se pudo interpretar el json de criterios que devolvio Groq", ex);
        }
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value.toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
