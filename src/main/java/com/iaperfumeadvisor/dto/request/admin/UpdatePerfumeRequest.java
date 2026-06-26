package com.iaperfumeadvisor.dto.request.admin;

import com.iaperfumeadvisor.enums.PerfumeCategory;
import com.iaperfumeadvisor.enums.PerfumeStatus;
import com.iaperfumeadvisor.enums.GenderType;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdatePerfumeRequest {

    private String name;
    private String brand;
    private String description;
    private PerfumeCategory category;
    private GenderType genderType;

    @Positive(message = "Price must be greater than 0")
    private BigDecimal price;

    @PositiveOrZero(message = "Stock cannot be negative")
    private Integer stock;

    private PerfumeStatus status;
    private String imageUrl;
}
