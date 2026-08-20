package com.iaperfumeadvisor.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendationResponse {
    private String detectedCategory;
    private String detectedGender;
    private boolean productIntent;
    private int totalMatches;
    private List<RecommendationItem> recommendations;
}
