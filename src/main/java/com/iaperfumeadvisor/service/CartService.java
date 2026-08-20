package com.iaperfumeadvisor.service;

import com.iaperfumeadvisor.dto.request.client.AddToCartRequest;
import com.iaperfumeadvisor.dto.request.client.UpdateCartItemRequest;
import com.iaperfumeadvisor.dto.response.CartResponse;
import com.iaperfumeadvisor.dto.response.WhatsAppRedirectResponse;

public interface CartService {

    CartResponse getCart(String cartId);

    CartResponse addItem(String cartId, AddToCartRequest request);

    CartResponse updateItem(String cartId, UpdateCartItemRequest request);

    CartResponse removeItem(String cartId, Long perfumeId);

    WhatsAppRedirectResponse checkout(String cartId);
}
