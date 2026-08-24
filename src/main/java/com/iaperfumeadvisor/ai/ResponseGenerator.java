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

        if (recommendations.isNeedsClarification()) {
            return "Contame un poco mas: es para vos o para regalar? te gusta algo mas dulce, fresco o amaderado?";
        }

        List<RecommendationItem> items = recommendations.getRecommendations();
        if (items.isEmpty()) {
            return "Por ahora no tenemos coincidencias en nuestro catalogo para esa busqueda. "
                    + "Contame si queres que te muestre otras opciones.";
        }

        String names = items.stream()
                .limit(3)
                .map(RecommendationItem::getName)
                .collect(Collectors.joining(", "));

        return "Encontre estas opciones que podrian interesarte: " + names + ".";
    }
}
