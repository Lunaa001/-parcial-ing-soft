package com.iaperfumeadvisor.config;

import com.iaperfumeadvisor.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * La primera llamada a Groq despues de arrancar el server tarda bastante mas
 * (conexion TLS nueva, etc.) que las siguientes. Disparamos una llamada minima
 * apenas termina de levantar la app para que esa demora no la pague el primer
 * cliente real que escribe.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroqWarmupInitializer {

    private final OpenAiService openAiService;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        CompletableFuture.runAsync(() -> {
            try {
                long start = System.currentTimeMillis();
                openAiService.generateChatResponse("Responde unicamente con: ok", List.of(), "hola", true);
                log.info("Groq precalentado en {} ms", System.currentTimeMillis() - start);
            } catch (Exception ex) {
                log.warn("No se pudo precalentar Groq: {}", ex.getMessage());
            }
        });
    }
}
