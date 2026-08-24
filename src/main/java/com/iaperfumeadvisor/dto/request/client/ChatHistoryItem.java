package com.iaperfumeadvisor.dto.request.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatHistoryItem {
    // "user" o "assistant"
    private String role;
    private String message;
}
