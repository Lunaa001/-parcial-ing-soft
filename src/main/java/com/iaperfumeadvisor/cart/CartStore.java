package com.iaperfumeadvisor.cart;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Guarda los carritos en memoria, identificados por un id que genera y persiste el cliente
 * (no hay login de clientes en esta app, solo de admin). No sobrevive a un reinicio del server;
 * si mas adelante hace falta persistencia entre reinicios, esto pasa a una tabla en base de datos.
 */
@Component
public class CartStore {

    private final Map<String, Cart> carts = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(1);

    public Cart getOrCreate(String cartId) {
        return carts.computeIfAbsent(cartId, id -> {
            List<CartItem> items = new CopyOnWriteArrayList<>();
            return new Cart(idSequence.getAndIncrement(), null, items, BigDecimal.ZERO);
        });
    }
}
