package com.iaperfumeadvisor.ai;

import com.iaperfumeadvisor.enums.GenderType;
import com.iaperfumeadvisor.enums.PerfumeCategory;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class PreferenceAnalyzer {

    private static final Map<String, PerfumeCategory> CATEGORY_KEYWORDS = Map.ofEntries(
            Map.entry("floral", PerfumeCategory.FLORAL),
            Map.entry("flores", PerfumeCategory.FLORAL),
            Map.entry("flor", PerfumeCategory.FLORAL),
            Map.entry("frutal", PerfumeCategory.FRUITY),
            Map.entry("frutado", PerfumeCategory.FRUITY),
            Map.entry("frutas", PerfumeCategory.FRUITY),
            Map.entry("afrutado", PerfumeCategory.FRUITY),
            Map.entry("dulce", PerfumeCategory.ORIENTAL),
            Map.entry("dulces", PerfumeCategory.ORIENTAL),
            Map.entry("oriental", PerfumeCategory.ORIENTAL),
            Map.entry("especiado", PerfumeCategory.ORIENTAL),
            Map.entry("amaderado", PerfumeCategory.WOODY),
            Map.entry("madera", PerfumeCategory.WOODY),
            Map.entry("fresco", PerfumeCategory.FRESH),
            Map.entry("frescor", PerfumeCategory.FRESH),
            Map.entry("verano", PerfumeCategory.FRESH),
            Map.entry("chipre", PerfumeCategory.CHYPRE),
            Map.entry("aromatico", PerfumeCategory.AROMATIC),
            Map.entry("herbal", PerfumeCategory.AROMATIC),
            Map.entry("citrico", PerfumeCategory.CITRUS),
            Map.entry("citricos", PerfumeCategory.CITRUS),
            Map.entry("limon", PerfumeCategory.CITRUS)
    );

    private static final Map<String, GenderType> GENDER_KEYWORDS = Map.of(
            "hombre", GenderType.MALE,
            "hombres", GenderType.MALE,
            "masculino", GenderType.MALE,
            "mujer", GenderType.FEMALE,
            "mujeres", GenderType.FEMALE,
            "femenino", GenderType.FEMALE,
            "unisex", GenderType.UNISEX
    );

    public PreferenceCriteria analyze(String message) {
        List<String> tokens = tokenize(message);

        PerfumeCategory category = tokens.stream()
                .map(CATEGORY_KEYWORDS::get)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        GenderType genderType = tokens.stream()
                .map(GENDER_KEYWORDS::get)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        return PreferenceCriteria.builder()
                .category(category)
                .genderType(genderType)
                .keywords(tokens)
                .build();
    }

    private List<String> tokenize(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        String normalized = Normalizer.normalize(message.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return Arrays.stream(normalized.split("[^a-z0-9]+"))
                .filter(token -> !token.isBlank())
                .toList();
    }
}
