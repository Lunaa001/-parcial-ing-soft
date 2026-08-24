package com.iaperfumeadvisor.controller.client;

import com.iaperfumeadvisor.dto.request.client.AddToCartRequest;
import com.iaperfumeadvisor.dto.request.client.UpdateCartItemRequest;
import com.iaperfumeadvisor.dto.response.CartResponse;
import com.iaperfumeadvisor.dto.response.WhatsAppRedirectResponse;
import com.iaperfumeadvisor.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * No hay login de clientes en esta app: cada dispositivo genera su propio id de carrito
 * (UUID) y lo manda en el header X-Cart-Id en cada request. El backend lo usa para
 * encontrar/crear el carrito correspondiente (ver CartStore).
 */
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public ResponseEntity<CartResponse> getCart(@RequestHeader("X-Cart-Id") String cartId) {
        return ResponseEntity.ok(cartService.getCart(cartId));
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(
            @RequestHeader("X-Cart-Id") String cartId,
            @Valid @RequestBody AddToCartRequest request) {
        return ResponseEntity.ok(cartService.addItem(cartId, request));
    }

    @PutMapping("/items")
    public ResponseEntity<CartResponse> updateItem(
            @RequestHeader("X-Cart-Id") String cartId,
            @Valid @RequestBody UpdateCartItemRequest request) {
        return ResponseEntity.ok(cartService.updateItem(cartId, request));
    }

    @DeleteMapping("/items/{perfumeId}")
    public ResponseEntity<CartResponse> removeItem(
            @RequestHeader("X-Cart-Id") String cartId,
            @PathVariable Long perfumeId) {
        return ResponseEntity.ok(cartService.removeItem(cartId, perfumeId));
    }

    @PostMapping("/checkout")
    public ResponseEntity<WhatsAppRedirectResponse> checkout(@RequestHeader("X-Cart-Id") String cartId) {
        return ResponseEntity.ok(cartService.checkout(cartId));
    }

    // Se llama aparte de /checkout, solo cuando el front confirma que WhatsApp se abrio bien.
    @DeleteMapping
    public ResponseEntity<CartResponse> clearCart(@RequestHeader("X-Cart-Id") String cartId) {
        return ResponseEntity.ok(cartService.clearCart(cartId));
    }
}
