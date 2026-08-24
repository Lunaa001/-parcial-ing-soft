package com.iaperfumeadvisor.service;

import com.iaperfumeadvisor.dto.request.client.ChatHistoryItem;

import java.util.List;

public interface OpenAiService {

    // allowSearch=false evita el modelo con busqueda agentica (compound) y usa un modelo de
    // texto plano en su lugar: mas rapido, sin riesgo de exceder el limite de tokens por
    // pedido cuando ademas tenemos que razonar contra varios productos del catalogo.
    String generateChatResponse(String systemInstruction, List<ChatHistoryItem> history, String currentMessage, boolean allowSearch);
}
