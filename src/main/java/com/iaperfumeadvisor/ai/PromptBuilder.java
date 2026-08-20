package com.iaperfumeadvisor.ai;

import com.iaperfumeadvisor.dto.response.RecommendationItem;
import com.iaperfumeadvisor.dto.response.RecommendationResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PromptBuilder {

    public String buildChatPrompt(String userMessage, RecommendationResponse recommendations) {
        if (!recommendations.isProductIntent()) {
            return buildSmallTalkPrompt(userMessage);
        }

        List<RecommendationItem> items = recommendations.getRecommendations();
        if (items.isEmpty()) {
            return buildNoMatchesPrompt(userMessage);
        }

        return buildRecommendationPrompt(userMessage, items);
    }

    private String buildSmallTalkPrompt(String userMessage) {
        return "Sos un asistente de ventas de una perfumeria, charlando por chat con un cliente. "
                + "Respondé de forma breve, cálida y natural en español, como una charla real, no como un discurso de ventas. "
                + "El cliente todavía no pidió ningún perfume ni dio pistas de lo que busca, así que por ahora NO recomiendes "
                + "ni menciones productos puntuales. Si tiene sentido, preguntale con naturalidad qué tipo de perfume busca "
                + "(para él/ella o para regalar, qué notas le gustan -dulce, fresco, amaderado-, o un presupuesto), pero sin "
                + "sonar a formulario ni repetir siempre la misma pregunta.\n\n"
                + "Mensaje del cliente: " + userMessage;
    }

    private String buildNoMatchesPrompt(String userMessage) {
        return "Sos un asistente de ventas de una perfumeria. Respondé de forma breve y amable en español. "
                + "El cliente pidió perfumes pero no tenemos ningún producto en el catálogo que coincida ahora mismo "
                + "(por categoría, género o stock). Decíselo con naturalidad, sin inventar productos, y pedile más detalle "
                + "o si quiere que le muestres otras opciones.\n\n"
                + "Mensaje del cliente: " + userMessage;
    }

    private String buildRecommendationPrompt(String userMessage, List<RecommendationItem> items) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Sos un asistente de ventas de una perfumeria, charlando por chat con un cliente. ")
                .append("Respondé de forma breve, cálida y natural en español.\n\n")
                .append("Reglas importantes:\n")
                .append("- Los productos que podés OFRECER Y VENDER son SOLO los listados abajo. Nunca inventes productos, ")
                .append("nunca menciones que hay stock o un precio distinto al que te doy.\n")
                .append("- Para describirlos (notas, aroma, para qué ocasión sirven, con qué se parecen) SÍ podés usar tu ")
                .append("conocimiento general sobre esos perfumes reales si los reconocés por nombre y marca, además de la ")
                .append("descripción y categorías cargadas. Si no reconocés el perfume puntual, describilo en base a sus ")
                .append("categorías/descripción sin inventar notas específicas.\n")
                .append("- Si el cliente menciona un perfume que usa o le gusta (de cualquier marca, este o no en nuestro ")
                .append("catálogo), usá tu conocimiento general sobre ese perfume real (familia olfativa, notas, estilo) ")
                .append("para juzgar cuál de los perfumes listados abajo se parece más, y explicá brevemente el porqué del ")
                .append("parecido. No hace falta que el perfume que menciona el cliente esté en la lista.\n")
                .append("- No hace falta que repitas precio y stock exactos en el texto (eso ya se muestra aparte); enfocate ")
                .append("en charlar y ayudar a elegir.\n\n")
                .append("Mensaje del cliente: ").append(userMessage).append("\n\n")
                .append("Perfumes que podés ofrecer ahora (usa solo estos):\n");

        for (RecommendationItem item : items) {
            prompt.append("- ").append(item.getName())
                    .append(" (").append(item.getBrand()).append("), categorias ")
                    .append(String.join(", ", item.getCategories())).append(", genero ")
                    .append(item.getGenderType()).append(", precio ")
                    .append(item.getPrice()).append(", stock ")
                    .append(item.getStock()).append("\n");
        }

        return prompt.toString();
    }
}
