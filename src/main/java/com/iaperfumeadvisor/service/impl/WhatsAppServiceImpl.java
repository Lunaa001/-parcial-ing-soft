package com.iaperfumeadvisor.service.impl;

import com.iaperfumeadvisor.dto.response.WhatsAppRedirectResponse;
import com.iaperfumeadvisor.service.WhatsAppService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class WhatsAppServiceImpl implements WhatsAppService {

    @Value("${whatsapp.business-number:}")
    private String businessNumber;

    @Override
    public WhatsAppRedirectResponse getWhatsAppRedirect(String message) {
        String encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8);
        String number = businessNumber == null ? "" : businessNumber.trim();
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
