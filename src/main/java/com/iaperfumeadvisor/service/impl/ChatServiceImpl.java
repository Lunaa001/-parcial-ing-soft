package com.iaperfumeadvisor.service.impl;

import com.iaperfumeadvisor.ai.PromptBuilder;
import com.iaperfumeadvisor.ai.ResponseGenerator;
import com.iaperfumeadvisor.dto.request.client.ChatRequest;
import com.iaperfumeadvisor.dto.request.client.RecommendationRequest;
import com.iaperfumeadvisor.dto.response.ChatResponse;
import com.iaperfumeadvisor.dto.response.RecommendationResponse;
import com.iaperfumeadvisor.exception.BusinessException;
import com.iaperfumeadvisor.service.ChatService;
import com.iaperfumeadvisor.service.OpenAiService;
import com.iaperfumeadvisor.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final RecommendationService recommendationService;
    private final PromptBuilder promptBuilder;
    private final OpenAiService openAiService;
    private final ResponseGenerator responseGenerator;

    @Override
    public ChatResponse sendMessage(ChatRequest request) {
        RecommendationResponse recommendations = recommendationService.getRecommendations(
                RecommendationRequest.builder().message(request.getMessage()).build());

        String prompt = promptBuilder.buildChatPrompt(request.getMessage(), recommendations);

        String naturalResponse;
        try {
            naturalResponse = openAiService.generateChatResponse(prompt);
        } catch (BusinessException ex) {
            log.warn("Fallo la llamada a OpenAI, se usa respuesta de reserva: {}", ex.getMessage());
            naturalResponse = responseGenerator.generateFallbackResponse(recommendations);
        }

        return ChatResponse.builder()
                .id(UUID.randomUUID().toString())
                .message(request.getMessage())
                .response(naturalResponse)
                .recommendations(recommendations.getRecommendations())
                .timestamp(LocalDateTime.now())
                .build();
    }
}
