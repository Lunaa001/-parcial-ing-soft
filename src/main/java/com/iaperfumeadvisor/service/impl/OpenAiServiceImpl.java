package com.iaperfumeadvisor.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaperfumeadvisor.dto.request.client.ChatHistoryItem;
import com.iaperfumeadvisor.exception.BusinessException;
import com.iaperfumeadvisor.service.OpenAiService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class OpenAiServiceImpl implements OpenAiService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${groq.api-key:}")
    private String apiKey;

    @Value("${groq.model:groq/compound}")
    private String model;

    // Modelo de texto plano (sin busqueda agentica) para cuando ademas hay que razonar contra
    // varios productos del catalogo: groq/compound ahi a veces dispara varias busquedas internas
    // (una por cada perfume que compara) y se pasa del limite de tokens por pedido del plan gratis.
    @Value("${groq.fallback-model:openai/gpt-oss-120b}")
    private String fallbackModel;

    @Value("${groq.api-url:https://api.groq.com/openai/v1}")
    private String apiBaseUrl;

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
}
