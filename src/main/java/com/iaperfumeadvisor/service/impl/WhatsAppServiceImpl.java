package com.iaperfumeadvisor.service.impl;

import com.iaperfumeadvisor.dto.response.WhatsAppRedirectResponse;
import com.iaperfumeadvisor.service.WhatsAppService;
import org.springframework.stereotype.Service;

@Service
public class WhatsAppServiceImpl implements WhatsAppService {

    @Override
    public WhatsAppRedirectResponse getWhatsAppRedirect(String message) {
        return WhatsAppRedirectResponse.builder()
                .whatsappUrl("https://wa.me/")
                .message(message)
                .build();
    }

    @Override
    public String generateWhatsAppMessage(String cartSummary) {
        return "Hello, I'm interested in: " + cartSummary;
    }
}
