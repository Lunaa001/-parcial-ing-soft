package com.iaperfumeadvisor.service;

import com.iaperfumeadvisor.dto.request.client.RecommendationRequest;
import com.iaperfumeadvisor.dto.response.RecommendationResponse;

public interface RecommendationService {

    RecommendationResponse getRecommendations(RecommendationRequest request);
}
