package com.iaperfumeadvisor.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

@Component
public class SecurityResponseWriter {

    public void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        String body = """
                {"timestamp":"%s","status":%d,"message":"%s"}""".formatted(
                LocalDateTime.now(), status.value(), escape(message));

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(body);
    }

    private String escape(String value) {
        return value.replace("\"", "'");
    }
}
