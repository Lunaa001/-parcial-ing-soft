package com.iaperfumeadvisor.ai;

import com.iaperfumeadvisor.dto.response.RecommendationItem;
import com.iaperfumeadvisor.dto.response.RecommendationResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class ResponseGenerator {

    public String generateFallbackResponse(RecommendationResponse recommendations) {
        if (!recommendations.isProductIntent()) {
            return "Hola! Contame que tipo de perfume estas buscando y te ayudo a encontrar algo.";
        }

        List<RecommendationItem> items = recommendations.getRecommendations();
        if (items.isEmpty()) {
            return "No encontre perfumes disponibles que coincidan con tu busqueda en este momento.";
        }

        String names = items.stream()
                .limit(3)
                .map(RecommendationItem::getName)
                .collect(Collectors.joining(", "));

        return "Encontre estas opciones que podrian interesarte: " + names + ".";
    }
}
