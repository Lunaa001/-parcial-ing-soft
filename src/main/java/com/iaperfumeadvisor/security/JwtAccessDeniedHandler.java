package com.iaperfumeadvisor.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

// Se dispara cuando el usuario esta autenticado pero no tiene el rol necesario (403), a
// diferencia de JwtAuthenticationEntryPoint que es para cuando ni siquiera hay login valido (401).
@Component
@RequiredArgsConstructor
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityResponseWriter responseWriter;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                        AccessDeniedException accessDeniedException) throws IOException {
        responseWriter.write(response, HttpStatus.FORBIDDEN, "You do not have permission to access this resource");
    }
}
