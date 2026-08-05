package com.iaperfumeadvisor.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendationItem {
    private Long perfumeId;
    private String name;
    private String brand;
    private String category;
    private String genderType;
    private BigDecimal price;
    private Integer stock;
    private Integer rating;
    private double matchScore;
}
