package com.iaperfumeadvisor.ai;

import com.iaperfumeadvisor.dto.response.RecommendationItem;
import com.iaperfumeadvisor.dto.response.RecommendationResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

// Respuesta de reserva cuando no se puede confiar en el texto de la IA (fallo la llamada a
// Groq, o el modelo alucino un producto/marca que no le dimos): ver ChatServiceImpl. Es texto
// fijo y simple, sin IA de por medio, para garantizar que el cliente siempre reciba algo fiel
// al catalogo real aunque el modelo falle o se equivoque.
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
