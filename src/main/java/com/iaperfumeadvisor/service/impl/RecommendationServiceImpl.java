package com.iaperfumeadvisor.service.impl;

import com.iaperfumeadvisor.ai.KnownReferenceFragrances;
import com.iaperfumeadvisor.ai.PreferenceAnalyzer;
import com.iaperfumeadvisor.ai.PreferenceCriteria;
import com.iaperfumeadvisor.ai.RecommendationEngine;
import com.iaperfumeadvisor.ai.ScoredPerfume;
import com.iaperfumeadvisor.dto.request.client.RecommendationRequest;
import com.iaperfumeadvisor.dto.response.RecommendationItem;
import com.iaperfumeadvisor.dto.response.RecommendationResponse;
import com.iaperfumeadvisor.entity.Perfume;
import com.iaperfumeadvisor.enums.PerfumeStatus;
import com.iaperfumeadvisor.exception.BusinessException;
import com.iaperfumeadvisor.mapper.PerfumeMapper;
import com.iaperfumeadvisor.repository.PerfumeRepository;
import com.iaperfumeadvisor.service.GroqService;
import com.iaperfumeadvisor.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Conecta el pipeline algoritmico (PreferenceAnalyzer + RecommendationEngine) con la capa de
// DTOs de la API: es el punto que expone el endpoint de recomendaciones y el que usa ChatService
// para saber que productos ofrecer antes de hablar con la IA.
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationServiceImpl implements RecommendationService {

    private final PreferenceAnalyzer preferenceAnalyzer;
    private final RecommendationEngine recommendationEngine;
    private final PerfumeMapper perfumeMapper;
    private final GroqService groqService;
    private final PerfumeRepository perfumeRepository;
    private final KnownReferenceFragrances knownReferenceFragrances;

    @Override
    public RecommendationResponse getRecommendations(RecommendationRequest request) {
        PreferenceCriteria criteria = resolveCriteria(request.getMessage());
        List<ScoredPerfume> matches = recommendationEngine.findMatches(criteria);

        List<RecommendationItem> items = matches.stream()
                .map(match -> perfumeMapper.toRecommendationItem(match.perfume(), match.score()))
                .toList();

        // Si el motor encontro productos concretos (por ejemplo, el cliente escribio el nombre
        // exacto de uno nuestro) tratamos el mensaje como intencion de compra real, sin importar
        // lo que haya detectado el analisis de palabras clave por si solo.
        boolean productIntent = criteria.isProductIntent() || !items.isEmpty();
        boolean needsClarification = criteria.isNeedsClarification() && items.isEmpty();

        return RecommendationResponse.builder()
                .detectedCategory(criteria.getCategory() != null ? criteria.getCategory().name() : null)
                .detectedGender(criteria.getGenderType() != null ? criteria.getGenderType().name() : null)
                .productIntent(productIntent)
                .needsClarification(needsClarification)
                .totalMatches(items.size())
                .recommendations(items)
                .build();
    }

    // El diccionario de palabras clave de PreferenceAnalyzer es instantaneo y gratis, pero es una
    // lista fija chica: no reconoce notas/pedidos que no anticipamos (ej. "vainilla") ni tolera
    // errores de tipeo. Le pedimos a la IA que interprete el mensaje en lenguaje natural y
    // combinamos ambos resultados; si Groq falla (sin API key, caida del servicio, etc.) seguimos
    // solo con el analisis por diccionario para no romper el chat.
    private PreferenceCriteria resolveCriteria(String message) {
        PreferenceCriteria dictionaryCriteria = preferenceAnalyzer.analyze(message);
        PreferenceCriteria criteria;
        try {
            List<String> catalogNames = perfumeRepository.findByStatusAndStockGreaterThan(PerfumeStatus.AVAILABLE, 0)
                    .stream()
                    .map(Perfume::getName)
                    .toList();
            PreferenceCriteria aiCriteria = groqService.extractCriteria(message, catalogNames);
            criteria = mergeWithAiCriteria(dictionaryCriteria, aiCriteria);
        } catch (BusinessException ex) {
            log.warn("No se pudo usar la IA para interpretar el mensaje, se sigue con el analisis por "
                    + "palabras clave: {}", ex.getMessage());
            criteria = dictionaryCriteria;
        }
        return applyKnownReference(message, criteria);
    }

    // Si el cliente nombro un perfume de otra marca que ya tenemos catalogado a mano (ver
    // KnownReferenceFragrances), esa clasificacion pisa lo que haya dicho la IA: es un dato fijo y
    // confiable en vez de depender de lo que el modelo "recuerde" de memoria en cada llamada, que
    // puede variar de una vez a otra para el mismo perfume. Tambien forzamos productIntent=true:
    // aunque a veces la IA no lo detecte bien para frases tipo "una alternativa a...", nombrar un
    // perfume conocido puntual ya es prueba de sobra de que quiere una recomendacion.
    private PreferenceCriteria applyKnownReference(String message, PreferenceCriteria criteria) {
        return knownReferenceFragrances.match(message)
                .map(known -> criteria.toBuilder()
                        .category(known.category())
                        .genderType(known.genderType())
                        .productIntent(true)
                        .build())
                .orElse(criteria);
    }

    // La categoria/genero/intencion de la IA pisan al diccionario cuando pudo interpretar el
    // mensaje (entiende mas casos y no cae en falsos positivos como el diccionario, que marca
    // productIntent=true solo por la palabra "tenes" aunque sea una pregunta de envios). Las
    // palabras clave si se unen: asi el matcheo de nombre exacto (RecommendationEngine) sigue
    // teniendo los tokens crudos del mensaje ademas de las notas/nombres que interpreto la IA.
    private PreferenceCriteria mergeWithAiCriteria(PreferenceCriteria dictionaryCriteria, PreferenceCriteria aiCriteria) {
        Set<String> mergedKeywords = new LinkedHashSet<>();
        if (dictionaryCriteria.getKeywords() != null) {
            mergedKeywords.addAll(dictionaryCriteria.getKeywords());
        }
        if (aiCriteria.getKeywords() != null) {
            mergedKeywords.addAll(aiCriteria.getKeywords());
        }

        return PreferenceCriteria.builder()
                .category(aiCriteria.getCategory() != null ? aiCriteria.getCategory() : dictionaryCriteria.getCategory())
                .genderType(aiCriteria.getGenderType() != null ? aiCriteria.getGenderType() : dictionaryCriteria.getGenderType())
                .keywords(new ArrayList<>(mergedKeywords))
                .productIntent(aiCriteria.isProductIntent())
                .needsClarification(aiCriteria.isNeedsClarification())
                .referencesSpecificPerfume(dictionaryCriteria.isReferencesSpecificPerfume() || aiCriteria.isReferencesSpecificPerfume())
                .build();
    }
}
