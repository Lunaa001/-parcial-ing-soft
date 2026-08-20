package com.iaperfumeadvisor.service.impl;

import com.iaperfumeadvisor.cart.Cart;
import com.iaperfumeadvisor.cart.CartItem;
import com.iaperfumeadvisor.cart.CartStore;
import com.iaperfumeadvisor.dto.request.client.AddToCartRequest;
import com.iaperfumeadvisor.dto.request.client.UpdateCartItemRequest;
import com.iaperfumeadvisor.dto.response.CartResponse;
import com.iaperfumeadvisor.dto.response.WhatsAppRedirectResponse;
import com.iaperfumeadvisor.entity.Perfume;
import com.iaperfumeadvisor.exception.BusinessException;
import com.iaperfumeadvisor.exception.InsufficientStockException;
import com.iaperfumeadvisor.exception.ResourceNotFoundException;
import com.iaperfumeadvisor.mapper.CartMapper;
import com.iaperfumeadvisor.repository.PerfumeRepository;
import com.iaperfumeadvisor.service.CartService;
import com.iaperfumeadvisor.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final CartStore cartStore;
    private final PerfumeRepository perfumeRepository;
    private final CartMapper cartMapper;
    private final WhatsAppService whatsAppService;

    @Override
    public CartResponse getCart(String cartId) {
        return cartMapper.toResponse(cartStore.getOrCreate(cartId));
    }

    @Override
    public CartResponse addItem(String cartId, AddToCartRequest request) {
        Perfume perfume = findPerfumeOrThrow(request.getPerfumeId());
        Cart cart = cartStore.getOrCreate(cartId);

        Optional<CartItem> existing = findItem(cart, perfume.getId());
        int requestedTotal = request.getQuantity() + existing.map(CartItem::getQuantity).orElse(0);
        assertStockAvailable(perfume, requestedTotal);

        if (existing.isPresent()) {
            existing.get().setQuantity(requestedTotal);
        } else {
            CartItem item = new CartItem();
            item.setId(perfume.getId());
            item.setPerfumeId(perfume.getId());
            item.setPerfumeName(perfume.getName());
            item.setImageUrl(perfume.getImageUrl());
            item.setQuantity(request.getQuantity());
            item.setPricePerUnit(perfume.getPrice());
            cart.getItems().add(item);
        }

        return cartMapper.toResponse(cart);
    }

    @Override
    public CartResponse updateItem(String cartId, UpdateCartItemRequest request) {
        Cart cart = cartStore.getOrCreate(cartId);
        CartItem item = findItem(cart, request.getCartItemId())
                .orElseThrow(() -> new ResourceNotFoundException("Item not found in cart: " + request.getCartItemId()));

        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            cart.getItems().remove(item);
            return cartMapper.toResponse(cart);
        }

        Perfume perfume = findPerfumeOrThrow(item.getPerfumeId());
        assertStockAvailable(perfume, request.getQuantity());
        item.setQuantity(request.getQuantity());

        return cartMapper.toResponse(cart);
    }

    @Override
    public CartResponse removeItem(String cartId, Long perfumeId) {
        Cart cart = cartStore.getOrCreate(cartId);
        CartItem item = findItem(cart, perfumeId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found in cart: " + perfumeId));
        cart.getItems().remove(item);
        return cartMapper.toResponse(cart);
    }

    @Override
    public WhatsAppRedirectResponse checkout(String cartId) {
        Cart cart = cartStore.getOrCreate(cartId);
        if (cart.getItems().isEmpty()) {
            throw new BusinessException("El carrito esta vacio");
        }

        String message = whatsAppService.generateWhatsAppMessage(buildCartSummary(cart));
        WhatsAppRedirectResponse redirect = whatsAppService.getWhatsAppRedirect(message);
        cart.getItems().clear();
        return redirect;
    }

    private Perfume findPerfumeOrThrow(Long perfumeId) {
        return perfumeRepository.findById(perfumeId)
                .orElseThrow(() -> new ResourceNotFoundException("Perfume not found with id: " + perfumeId));
    }

    private Optional<CartItem> findItem(Cart cart, Long perfumeId) {
        return cart.getItems().stream()
                .filter(item -> item.getPerfumeId().equals(perfumeId))
                .findFirst();
    }

    private void assertStockAvailable(Perfume perfume, int requestedQuantity) {
        if (requestedQuantity > perfume.getStock()) {
            throw new InsufficientStockException("No hay stock suficiente de " + perfume.getName());
        }
    }

    private String buildCartSummary(Cart cart) {
        StringBuilder summary = new StringBuilder();
        for (CartItem item : cart.getItems()) {
            summary.append(item.getQuantity())
                    .append("x ")
                    .append(item.getPerfumeName())
                    .append(" ($")
                    .append(item.getTotalPrice())
                    .append(")\n");
        }
        summary.append("Total: $").append(cart.calculateTotal());
        return summary.toString();
    }
}
