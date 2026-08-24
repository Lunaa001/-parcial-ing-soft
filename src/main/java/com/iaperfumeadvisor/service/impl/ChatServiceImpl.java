package com.iaperfumeadvisor.service.impl;

import com.iaperfumeadvisor.ai.PromptBuilder;
import com.iaperfumeadvisor.ai.ResponseGenerator;
import com.iaperfumeadvisor.dto.request.client.ChatHistoryItem;
import com.iaperfumeadvisor.dto.request.client.ChatRequest;
import com.iaperfumeadvisor.dto.request.client.RecommendationRequest;
import com.iaperfumeadvisor.dto.response.ChatResponse;
import com.iaperfumeadvisor.dto.response.RecommendationItem;
import com.iaperfumeadvisor.dto.response.RecommendationResponse;
import com.iaperfumeadvisor.exception.BusinessException;
import com.iaperfumeadvisor.service.ChatService;
import com.iaperfumeadvisor.service.OpenAiService;
import com.iaperfumeadvisor.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    // Alcanza para que la IA tenga contexto sin mandar un prompt gigante en cada mensaje.
    private static final int MAX_HISTORY_TURNS = 12;

    private final RecommendationService recommendationService;
    private final PromptBuilder promptBuilder;
    private final OpenAiService openAiService;
    private final ResponseGenerator responseGenerator;

    // Cuantos mensajes previos del cliente sumamos para reintentar el analisis cuando el mensaje
    // actual solo no alcanza (ver mas abajo).
    private static final int CONTEXT_MESSAGES_FOR_RETRY = 3;

    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*([^*]+)\\*\\*");

    // Marcas de otros negocios que la IA podria conocer y mencionar de memoria/busqueda para
    // "explicar" nuestros productos, aunque el cliente no las haya pedido. Si aparecen sin que el
    // cliente las haya nombrado el mismo, lo tratamos como una recomendacion no autorizada.
    private static final Set<String> KNOWN_OTHER_BRANDS = Set.of(
            "dior", "sauvage", "chanel", "tom ford", "tobacco vanille", "creed", "aventus",
            "ysl", "yves saint laurent", "libre", "armani", "giorgio armani",
            "dolce & gabbana", "dolce y gabbana", "d&g", "light blue", "versace",
            "paco rabanne", "carolina herrera", "montblanc", "mont blanc", "hugo boss",
            "burberry", "calvin klein", "prada", "gucci", "valentino", "bvlgari", "bulgari",
            "guerlain", "maison francis kurkdjian", "baccarat rouge", "parfums de marly",
            "althair", "xerjoff", "amouage", "initio", "jean paul gaultier", "issey miyake",
            "azzaro", "viktor & rolf", "narciso rodriguez", "elie saab", "lancome", "givenchy",
            "montale", "mancera", "nishane", "byredo", "good girl", "la vie est belle",
            "black opium", "club de nuit"
    );

    @Override
    public ChatResponse sendMessage(ChatRequest request) {
        List<ChatHistoryItem> history = trimHistory(request.getHistory());

        RecommendationResponse recommendations = recommendationService.getRecommendations(
                RecommendationRequest.builder().message(request.getMessage()).build());

        // "¿Cual dura mas?", "¿y el mas barato?", etc: el mensaje solo no tiene ninguna pista de
        // perfumeria, pero es claramente una continuacion de lo que se venia hablando. En vez de
        // mandarlo a charla generica (perdiendo el hilo), lo reanalizamos sumando los ultimos
        // mensajes del cliente para recuperar el contexto de la conversacion.
        if (!recommendations.isProductIntent() && hasUserHistory(history)) {
            RecommendationResponse withContext = recommendationService.getRecommendations(
                    RecommendationRequest.builder().message(buildContextualMessage(request.getMessage(), history)).build());
            if (withContext.isProductIntent()) {
                recommendations = withContext;
            }
        }

        String systemInstruction = promptBuilder.buildChatPrompt(recommendations);

        // Cuando hay productos concretos del catalogo para comparar, el modelo con busqueda
        // agentica a veces investiga cada uno por separado y se pasa del limite de tokens por
        // pedido: en ese caso usamos un modelo de texto plano (sin busqueda) que ya reconoce
        // bastante bien los perfumes conocidos con su propio conocimiento general.
        boolean allowSearch = recommendations.getRecommendations() == null
                || recommendations.getRecommendations().isEmpty();

        String naturalResponse;
        try {
            naturalResponse = openAiService.generateChatResponse(systemInstruction, history, request.getMessage(), allowSearch);
            List<RecommendationItem> items = recommendations.getRecommendations();
            if (items != null && !items.isEmpty() && mentionsProductNotInCatalog(naturalResponse, recommendations)) {
                log.warn("La IA menciono productos fuera del catalogo dado, se usa respuesta de reserva");
                naturalResponse = responseGenerator.generateFallbackResponse(recommendations);
            } else if ((items == null || items.isEmpty()) && mentionsInventedProduct(naturalResponse, request, history)) {
                log.warn("La IA parece haber inventado un producto propio sin coincidencias reales, se usa respuesta de reserva");
                naturalResponse = responseGenerator.generateFallbackResponse(recommendations);
            } else if (mentionsUnrequestedBrand(naturalResponse, request, history)) {
                log.warn("La IA menciono una marca de otro negocio sin que el cliente la pidiera, se usa respuesta de reserva");
                naturalResponse = responseGenerator.generateFallbackResponse(recommendations);
            }
        } catch (BusinessException ex) {
            log.warn("Fallo la llamada a OpenAI, se usa respuesta de reserva: {}", ex.getMessage());
            naturalResponse = responseGenerator.generateFallbackResponse(recommendations);
        }

        return ChatResponse.builder()
                .id(UUID.randomUUID().toString())
                .message(request.getMessage())
                .response(naturalResponse)
                .recommendations(recommendations.getRecommendations())
                .timestamp(LocalDateTime.now())
                .build();
    }

    // Salvavidas ante alucinaciones: si le dimos productos concretos para ofrecer pero la
    // respuesta de la IA no menciona a NINGUNO de ellos por nombre, es que se fue por las
    // ramas (inventando otros perfumes de memoria) en vez de usar la lista que le dimos.
    // En ese caso preferimos la respuesta de reserva, que es siempre fiel al catalogo real.
    private boolean mentionsProductNotInCatalog(String naturalResponse, RecommendationResponse recommendations) {
        List<RecommendationItem> items = recommendations.getRecommendations();
        if (items == null || items.isEmpty()) {
            return false;
        }
        String lowerResponse = naturalResponse.toLowerCase();
        return items.stream().noneMatch(item -> lowerResponse.contains(item.getName().toLowerCase()));
    }

    // Cuando no hay NINGUN producto real para ofrecer, no hay ningun motivo legitimo para que la
    // IA escriba un nombre propio en negrita (eso es lo que le pedimos que haga para nombres de
    // producto, ver FORMAT_NOTE) salvo que sea repitiendo un nombre que el propio cliente escribio.
    // Si aparece cualquier otro, es casi seguro un producto inventado (ver bug real: "Eau de Parfum
    // N°8", "Blossom Scent" que no existen en el catalogo).
    private boolean mentionsInventedProduct(String naturalResponse, ChatRequest request, List<ChatHistoryItem> history) {
        String customerWords = buildCustomerOwnWords(request, history);
        Matcher matcher = BOLD_PATTERN.matcher(naturalResponse);
        while (matcher.find()) {
            String candidate = matcher.group(1).trim().toLowerCase();
            if (candidate.length() < 3) {
                continue;
            }
            if (!customerWords.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    // Si la IA nombra una marca de otro negocio (Dior, Tom Ford, etc.) sin que el cliente la haya
    // mencionado el mismo, es una recomendacion no autorizada: confunde al cliente sobre que le
    // estamos ofreciendo realmente. Solo esta permitido si el cliente pidio esa comparacion puntual.
    private boolean mentionsUnrequestedBrand(String naturalResponse, ChatRequest request, List<ChatHistoryItem> history) {
        String customerWords = buildCustomerOwnWords(request, history);
        String lowerResponse = naturalResponse.toLowerCase();
        return KNOWN_OTHER_BRANDS.stream()
                .anyMatch(brand -> lowerResponse.contains(brand) && !customerWords.contains(brand));
    }

    private String buildCustomerOwnWords(ChatRequest request, List<ChatHistoryItem> history) {
        StringBuilder sb = new StringBuilder();
        if (request.getMessage() != null) {
            sb.append(request.getMessage().toLowerCase());
        }
        if (history != null) {
            for (ChatHistoryItem item : history) {
                if ("user".equalsIgnoreCase(item.getRole()) && item.getMessage() != null) {
                    sb.append(' ').append(item.getMessage().toLowerCase());
                }
            }
        }
        return sb.toString();
    }

    private boolean hasUserHistory(List<ChatHistoryItem> history) {
        return history != null && history.stream().anyMatch(item -> "user".equalsIgnoreCase(item.getRole()));
    }

    // Une los ultimos mensajes del cliente (los mas recientes primero perdidos, para no diluir
    // el pedido actual con algo que dijo hace rato) con el mensaje actual, para volver a analizar
    // con ese contexto extra.
    private String buildContextualMessage(String currentMessage, List<ChatHistoryItem> history) {
        List<String> recentUserMessages = history.stream()
                .filter(item -> "user".equalsIgnoreCase(item.getRole()))
                .map(ChatHistoryItem::getMessage)
                .filter(msg -> msg != null && !msg.isBlank())
                .toList();

        int from = Math.max(0, recentUserMessages.size() - CONTEXT_MESSAGES_FOR_RETRY);
        String context = String.join(". ", recentUserMessages.subList(from, recentUserMessages.size()));
        return context.isBlank() ? currentMessage : context + ". " + currentMessage;
    }

    private List<ChatHistoryItem> trimHistory(List<ChatHistoryItem> history) {
        if (history == null || history.size() <= MAX_HISTORY_TURNS) {
            return history;
        }
        return history.subList(history.size() - MAX_HISTORY_TURNS, history.size());
    }
}
