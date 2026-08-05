package com.iaperfumeadvisor.ai;

import com.iaperfumeadvisor.dto.response.RecommendationItem;
import com.iaperfumeadvisor.dto.response.RecommendationResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PromptBuilder {

    public String buildChatPrompt(String userMessage, RecommendationResponse recommendations) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Sos un asistente de ventas de una perfumeria. ")
                .append("Respondé de forma breve y amable en español, basándote UNICAMENTE en los perfumes listados abajo. ")
                .append("Nunca inventes perfumes ni menciones productos que no esten en la lista. ")
                .append("Si la lista esta vacia, indica amablemente que no hay coincidencias disponibles en este momento.\n\n")
                .append("Mensaje del cliente: ").append(userMessage).append("\n\n");

        List<RecommendationItem> items = recommendations.getRecommendations();
        if (items.isEmpty()) {
            prompt.append("Perfumes disponibles: ninguno coincide con la busqueda actual.");
        } else {
            prompt.append("Perfumes disponibles (usa solo estos):\n");
            for (RecommendationItem item : items) {
                prompt.append("- ").append(item.getName())
                        .append(" (").append(item.getBrand()).append("), categoria ")
                        .append(item.getCategory()).append(", genero ")
                        .append(item.getGenderType()).append(", precio ")
                        .append(item.getPrice()).append(", stock ")
                        .append(item.getStock()).append("\n");
            }
        }

        return prompt.toString();
    }
}
