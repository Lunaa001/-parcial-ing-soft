package com.iaperfumeadvisor.controller.client;

import com.iaperfumeadvisor.dto.request.client.RecommendationRequest;
import com.iaperfumeadvisor.dto.response.RecommendationResponse;
import com.iaperfumeadvisor.service.RecommendationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Expone el motor de recomendaciones "puro" (sin redaccion de IA) por si el frontend necesita
// los matches crudos; el chat conversacional (ChatController) lo usa por dentro, no via HTTP.
@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    @PostMapping
    public ResponseEntity<RecommendationResponse> getRecommendations(@Valid @RequestBody RecommendationRequest request) {
        return ResponseEntity.ok(recommendationService.getRecommendations(request));
    }
}
