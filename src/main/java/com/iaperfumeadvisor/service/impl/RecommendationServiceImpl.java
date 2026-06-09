package com.iaperfumeadvisor.service.impl;

import com.iaperfumeadvisor.dto.request.client.RecommendationRequest;
import com.iaperfumeadvisor.dto.response.RecommendationResponse;
import com.iaperfumeadvisor.service.RecommendationService;
import org.springframework.stereotype.Service;

@Service
public class RecommendationServiceImpl implements RecommendationService {

    @Override
    public RecommendationResponse getRecommendations(RecommendationRequest request) {
        return RecommendationResponse.builder()
                .perfumeId(1L)
                .perfumeName("Sample Perfume")
                .matchScore(85.0)
                .reason("Based on your preferences")
                .build();
    }

    @Override
    public RecommendationResponse getRecommendationsByPreferences(String preferences) {
        return RecommendationResponse.builder()
                .perfumeId(1L)
                .perfumeName("Recommended Perfume")
                .matchScore(90.0)
                .reason("Recommendations based on preferences: " + preferences)
                .build();
    }
}
