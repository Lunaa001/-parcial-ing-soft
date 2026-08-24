package com.iaperfumeadvisor.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

// Version publica/API de PreferenceCriteria + los matches ya resueltos: PromptBuilder y el
// frontend usan productIntent/needsClarification para decidir si mostrar productos, pedir mas
// info, o responder charla general.
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendationResponse {
    private String detectedCategory;
    private String detectedGender;
    private boolean productIntent;
    private boolean needsClarification;
    private int totalMatches;
    private List<RecommendationItem> recommendations;
}
