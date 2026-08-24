package com.iaperfumeadvisor.service.impl;

import com.iaperfumeadvisor.ai.PreferenceAnalyzer;
import com.iaperfumeadvisor.ai.PreferenceCriteria;
import com.iaperfumeadvisor.ai.RecommendationEngine;
import com.iaperfumeadvisor.ai.ScoredPerfume;
import com.iaperfumeadvisor.dto.request.client.RecommendationRequest;
import com.iaperfumeadvisor.dto.response.RecommendationItem;
import com.iaperfumeadvisor.dto.response.RecommendationResponse;
import com.iaperfumeadvisor.mapper.PerfumeMapper;
import com.iaperfumeadvisor.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RecommendationServiceImpl implements RecommendationService {

    private final PreferenceAnalyzer preferenceAnalyzer;
    private final RecommendationEngine recommendationEngine;
    private final PerfumeMapper perfumeMapper;

    @Override
    public RecommendationResponse getRecommendations(RecommendationRequest request) {
        PreferenceCriteria criteria = preferenceAnalyzer.analyze(request.getMessage());
        List<ScoredPerfume> matches = recommendationEngine.findMatches(criteria);

        List<RecommendationItem> items = matches.stream()
                .map(match -> perfumeMapper.toRecommendationItem(match.perfume(), match.score()))
                .toList();

        // Si el motor encontro productos concretos (por ejemplo, el cliente escribio el nombre
        // exacto de uno nuestro) tratamos el mensaje como intencion de compra real, sin importar
        // lo que haya detectado el analisis de palabras clave por si solo.
        boolean productIntent = criteria.isProductIntent() || !items.isEmpty();
        boolean needsClarification = criteria.isNeedsClarification() && items.isEmpty();

        return RecommendationResponse.builder()
                .detectedCategory(criteria.getCategory() != null ? criteria.getCategory().name() : null)
                .detectedGender(criteria.getGenderType() != null ? criteria.getGenderType().name() : null)
                .productIntent(productIntent)
                .needsClarification(needsClarification)
                .totalMatches(items.size())
                .recommendations(items)
                .build();
    }
}
