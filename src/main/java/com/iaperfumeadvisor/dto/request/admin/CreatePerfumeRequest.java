package com.iaperfumeadvisor.dto.request.admin;

import com.iaperfumeadvisor.enums.PerfumeCategory;
import com.iaperfumeadvisor.enums.GenderType;
import com.iaperfumeadvisor.enums.PerfumeStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreatePerfumeRequest {
    
    @NotBlank(message = "Name is required")
    private String name;
    
    @NotBlank(message = "Brand is required")
    private String brand;
    
    private String description;
    
    @NotNull(message = "Category is required")
    private PerfumeCategory category;
    
    @NotNull(message = "Gender type is required")
    private GenderType genderType;
    
    @NotNull(message = "Price is required")
    @Positive(message = "Price must be greater than 0")
    private BigDecimal price;
    
    @NotNull(message = "Stock is required")
    @PositiveOrZero(message = "Stock cannot be negative")
    private Integer stock;
    
    @NotNull(message = "Status is required")
    private PerfumeStatus status;
    
    private String imageUrl;
}
