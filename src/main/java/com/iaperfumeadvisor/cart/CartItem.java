package com.iaperfumeadvisor.cart;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CartItem {

    private Long id;
    private Long perfumeId;
    private String perfumeName;
    private String imageUrl;
    private Integer quantity;
    private BigDecimal pricePerUnit;

    public BigDecimal getTotalPrice() {
        return pricePerUnit.multiply(new BigDecimal(quantity));
    }
}
