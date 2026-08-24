package com.iaperfumeadvisor.mapper;

import com.iaperfumeadvisor.cart.Cart;
import com.iaperfumeadvisor.cart.CartItem;
import com.iaperfumeadvisor.dto.response.CartItemResponse;
import com.iaperfumeadvisor.dto.response.CartResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

// Traduce el carrito interno (Cart/CartItem, en memoria) al DTO que ve el cliente por API.
@Component
public class CartMapper {

    public CartResponse toResponse(Cart cart) {
        List<CartItemResponse> items = cart.getItems().stream()
                .map(this::toItemResponse)
                .collect(Collectors.toList());

        int totalItems = cart.getItems().stream()
                .mapToInt(CartItem::getQuantity)
                .sum();

        return CartResponse.builder()
                .id(cart.getId())
                .items(items)
                .totalPrice(cart.calculateTotal())
                .totalItems(totalItems)
                .build();
    }

    private CartItemResponse toItemResponse(CartItem item) {
        return CartItemResponse.builder()
                .perfumeId(item.getPerfumeId())
                .perfumeName(item.getPerfumeName())
                .imageUrl(item.getImageUrl())
                .quantity(item.getQuantity())
                .price(item.getPricePerUnit())
                .build();
    }
}
