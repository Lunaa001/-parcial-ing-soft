package com.iaperfumeadvisor.service;

import com.iaperfumeadvisor.dto.request.client.ChatRequest;
import com.iaperfumeadvisor.dto.response.ChatResponse;

public interface ChatService {

    ChatResponse sendMessage(ChatRequest request);
}
