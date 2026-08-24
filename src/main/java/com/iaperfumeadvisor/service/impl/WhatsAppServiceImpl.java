package com.iaperfumeadvisor.service.impl;

import com.iaperfumeadvisor.dto.response.WhatsAppRedirectResponse;
import com.iaperfumeadvisor.exception.BusinessException;
import com.iaperfumeadvisor.service.WhatsAppService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

// El "checkout" real de esta app es un link de WhatsApp (wa.me) con el pedido pre-armado en el
// texto, no un procesador de pagos: el negocio cierra la venta por chat directo con el cliente.
@Service
public class WhatsAppServiceImpl implements WhatsAppService {

    @Value("${whatsapp.business-number:}")
    private String businessNumber;

    @Override
    public WhatsAppRedirectResponse getWhatsAppRedirect(String message) {
        String number = businessNumber == null ? "" : businessNumber.trim();
        if (number.isEmpty()) {
            // Sin numero configurado el link quedaria roto (wa.me/?text=...) y el cliente
            // no podria completar el pedido: mejor fallar ahora que en el momento del checkout.
            throw new BusinessException("Numero de WhatsApp del negocio no configurado (whatsapp.business-number)");
        }

        String encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8);
        String url = "https://wa.me/" + number + "?text=" + encodedMessage;

        return WhatsAppRedirectResponse.builder()
                .whatsappUrl(url)
                .message(message)
                .build();
    }

    @Override
    public String generateWhatsAppMessage(String cartSummary) {
        return "Hola! Quiero hacer este pedido:\n\n" + cartSummary;
    }
}
