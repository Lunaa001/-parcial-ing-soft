package com.iaperfumeadvisor.dto.response;

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
public class PerfumeResponse {
    private Long id;
    private String name;
    private String brand;
    private String description;
    private List<String> categories;
    private String genderType;
    private BigDecimal price;
    private Integer stock;
    private String status;
    private String imageUrl;
    private Integer rating;
}
