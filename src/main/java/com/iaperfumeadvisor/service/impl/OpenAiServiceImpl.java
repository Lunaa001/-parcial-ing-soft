package com.iaperfumeadvisor.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;

@Service
public class OpenAiServiceImpl implements OpenAiService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-flash-lite-latest}")
    private String model;

    @Value("${gemini.api-url:https://generativelanguage.googleapis.com/v1beta}")
    private String apiBaseUrl;

    @Override
    public String generateChatResponse(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException("Gemini API key no configurada (gemini.api-key)");
        }

        try {
            // maxOutputTokens acota la respuesta a algo del largo de un mensaje de chat real:
            // ademas de leerse mejor, un modelo que genera menos tokens responde bastante mas rapido.
            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(Map.of("text", prompt))
                    )),
                    "generationConfig", Map.of("maxOutputTokens", 220)
            );

            String url = apiBaseUrl + "/models/" + model + ":generateContent?key=" + apiKey;

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new BusinessException("Gemini respondio con error " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            return extractText(root);

        } catch (IOException ex) {
            throw new BusinessException("Error de comunicacion con Gemini", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Llamada a Gemini interrumpida", ex);
        }
    }

    // Algunos modelos (los que tienen "thinking") devuelven primero una parte con su
    // razonamiento interno (marcada "thought": true) y despues la respuesta real.
    // Nos quedamos solo con las partes que NO son razonamiento.
    private String extractText(JsonNode root) {
        JsonNode parts = root.path("candidates").get(0).path("content").path("parts");
        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            if (part.path("thought").asBoolean(false)) {
                continue;
            }
            text.append(part.path("text").asText(""));
        }
        return text.toString().trim();
    }
}
