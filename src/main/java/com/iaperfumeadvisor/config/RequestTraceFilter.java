package com.iaperfumeadvisor.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

// Traza cada request: un id por peticion (en el log y en el header X-Request-Id) y su duracion,
// para poder diagnosticar latencias y fallas desde los logs del contenedor.
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String HEALTH_PATH = "/actuator/health";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            // Las probes de Azure y la prueba de humo consultan el health todo el tiempo: no se
            // registran para no tapar los logs de las requests reales (igual llevan X-Request-Id)
            if (!request.getRequestURI().startsWith(HEALTH_PATH)) {
                long durationMs = (System.nanoTime() - start) / 1_000_000;
                log.info("{} {} -> {} en {} ms", sanitize(request.getMethod()), sanitize(request.getRequestURI()),
                        response.getStatus(), durationMs);
            }
            MDC.remove("requestId");
        }
    }

    // El metodo y la URL los manda el cliente: se reemplazan los saltos de linea y demas caracteres
    // de control para que nadie pueda inyectar lineas falsas en el log (log injection, CWE-117).
    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', '_').replace('\n', '_').replaceAll("\\p{Cntrl}", "_");
    }
}
