package com.iaperfumeadvisor.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;

// JwtAuthenticationEntryPoint y JwtAccessDeniedHandler corren antes de que el DispatcherServlet
// (y por lo tanto GlobalExceptionHandler) entre en juego, asi que arman el JSON de error a mano
// aca para que la forma de la respuesta sea la misma que la del resto de la API.
@Component
public class SecurityResponseWriter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        // Se usa Jackson para escapar el mensaje (en vez de un reemplazo a mano de comillas):
        // un mensaje de error con saltos de linea, backslashes o comillas dobles podia romper
        // el JSON armado manualmente antes.
        String body = objectMapper.writeValueAsString(Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "status", status.value(),
                "message", message));

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(body);
    }
}
