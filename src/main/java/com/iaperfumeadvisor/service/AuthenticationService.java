package com.iaperfumeadvisor.service;

import com.iaperfumeadvisor.dto.request.auth.LoginRequest;
import com.iaperfumeadvisor.dto.request.auth.RegisterRequest;
import com.iaperfumeadvisor.dto.response.AuthResponse;

public interface AuthenticationService {

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);
}
