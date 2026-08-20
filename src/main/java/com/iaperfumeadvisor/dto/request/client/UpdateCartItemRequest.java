package com.iaperfumeadvisor.dto.request.client;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateCartItemRequest {

    @NotNull(message = "Cart item id is required")
    private Long cartItemId;

    // Null o <= 0 quita el item del carrito.
    private Integer quantity;
}
