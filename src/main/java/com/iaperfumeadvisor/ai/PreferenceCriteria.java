package com.iaperfumeadvisor.ai;

import com.iaperfumeadvisor.enums.GenderType;
import com.iaperfumeadvisor.enums.PerfumeCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PreferenceCriteria {
    private PerfumeCategory category;
    private GenderType genderType;
    private String brand;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private Integer minRating;
    private List<String> keywords;
    // Si el mensaje realmente pide perfumes/recomendaciones (vs. saludos, agradecimientos, charla general).
    private boolean productIntent;
}
