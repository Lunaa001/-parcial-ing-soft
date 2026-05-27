package com.iaperfumeadvisor.validator;

import com.iaperfumeadvisor.dto.request.admin.LoginRequest;
import com.iaperfumeadvisor.exception.InvalidInputException;
import org.springframework.stereotype.Component;

@Component
public class AuthValidator {

    public void validate(LoginRequest request) {
        if (request == null || request.getUsername() == null || request.getUsername().trim().isEmpty()) {
            throw new InvalidInputException("Username cannot be empty");
        }
        if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            throw new InvalidInputException("Password cannot be empty");
        }
    }
}
