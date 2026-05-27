package com.iaperfumeadvisor.validator;

import com.iaperfumeadvisor.dto.request.client.AddToCartRequest;
import com.iaperfumeadvisor.exception.InvalidInputException;
import org.springframework.stereotype.Component;

@Component
public class CartValidator {

    public void validate(AddToCartRequest request) {
        if (request == null || request.getPerfumeId() == null) {
            throw new InvalidInputException("Perfume ID cannot be null");
        }
        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new InvalidInputException("Quantity must be greater than 0");
        }
    }
}
