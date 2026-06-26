package com.iaperfumeadvisor.service.impl;

import com.iaperfumeadvisor.dto.request.auth.LoginRequest;
import com.iaperfumeadvisor.dto.request.auth.RegisterRequest;
import com.iaperfumeadvisor.dto.response.AuthResponse;
import com.iaperfumeadvisor.entity.User;
import com.iaperfumeadvisor.exception.UnauthorizedException;
import com.iaperfumeadvisor.security.JwtService;
import com.iaperfumeadvisor.service.AuthenticationService;
import com.iaperfumeadvisor.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthenticationServiceImpl implements AuthenticationService {

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final JwtService jwtService;

    @Override
    public AuthResponse register(RegisterRequest request) {
        User user = userService.createUser(request);
        return buildAuthResponse(user);
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
        } catch (BadCredentialsException ex) {
            throw new UnauthorizedException("Invalid username or password");
        } catch (DisabledException ex) {
            throw new UnauthorizedException("User account is disabled");
        }

        User user = userService.getUserEntityByUsername(request.getUsername());
        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        String token = jwtService.generateToken(user.getUsername(), user.getRole().name());
        return AuthResponse.builder()
                .token(token)
                .type("Bearer")
                .expiresIn(jwtService.getExpirationMs())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();
    }
}
