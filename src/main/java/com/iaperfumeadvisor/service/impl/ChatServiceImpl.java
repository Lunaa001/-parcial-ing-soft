package com.iaperfumeadvisor.service.impl;

import com.iaperfumeadvisor.dto.request.client.ChatRequest;
import com.iaperfumeadvisor.dto.response.ChatResponse;
import com.iaperfumeadvisor.service.ChatService;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class ChatServiceImpl implements ChatService {

    @Override
    public ChatResponse sendMessage(ChatRequest request) {
        return ChatResponse.builder()
                .id(UUID.randomUUID().toString())
                .message(request.getMessage())
                .response(processUserInput(request.getMessage()))
                .timestamp(LocalDateTime.now())
                .build();
    }

    @Override
    public String processUserInput(String message) {
        return "Response to: " + message;
    }
}
