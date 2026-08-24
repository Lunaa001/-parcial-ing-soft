package com.iaperfumeadvisor.service;

import com.iaperfumeadvisor.dto.request.client.ChatHistoryItem;

import java.util.List;

// Abstrae la llamada al proveedor de IA (Groq) para que ChatServiceImpl no dependa del detalle
// de la API HTTP; la implementacion real esta en GroqServiceImpl.
public interface GroqService {

    // allowSearch=false evita el modelo con busqueda agentica (compound) y usa un modelo de
    // texto plano en su lugar: mas rapido, sin riesgo de exceder el limite de tokens por
    // pedido cuando ademas tenemos que razonar contra varios productos del catalogo.
    String generateChatResponse(String systemInstruction, List<ChatHistoryItem> history, String currentMessage, boolean allowSearch);
}
