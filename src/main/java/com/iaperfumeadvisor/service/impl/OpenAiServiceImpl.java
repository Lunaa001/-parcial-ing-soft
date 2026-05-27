package com.iaperfumeadvisor.service.impl;

import com.iaperfumeadvisor.service.OpenAiService;
import org.springframework.stereotype.Service;

@Service
public class OpenAiServiceImpl implements OpenAiService {

    @Override
    public String generateResponse(String prompt) {
        return "Response to prompt: " + prompt;
    }

    @Override
    public String analyzePreferences(String userInput) {
        return "Analysis of preferences: " + userInput;
    }

    @Override
    public String generateRecommendation(String preferences) {
        return "Recommendations based on: " + preferences;
    }
}
