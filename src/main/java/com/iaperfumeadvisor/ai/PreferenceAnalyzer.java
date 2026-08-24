package com.iaperfumeadvisor.ai;

import com.iaperfumeadvisor.enums.GenderType;
import com.iaperfumeadvisor.enums.PerfumeCategory;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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

    // Palabras que indican que la persona esta pidiendo perfumes/recomendaciones,
    // a diferencia de un saludo, agradecimiento o charla general.
    private static final Set<String> INTENT_KEYWORDS = Set.of(
            "perfume", "perfumes", "fragancia", "fragancias", "aroma", "aromas",
            "locion", "lociones", "colonia", "colonias", "esencia",
            "recomendar", "recomendame", "recomendacion", "recomendaciones", "recomiendame", "recomendas",
            "busco", "buscando", "buscar", "quiero", "necesito", "queria", "tenes", "tienen",
            "regalo", "regalar", "opciones", "sugerencia", "sugerime", "sugerencias",
            "comprar", "precio", "precios", "catalogo", "disponible", "disponibles",
            "similar", "similares", "parecido", "parecidos", "parecida", "parecidas", "uso", "usaba"
    );

    // Si el cliente usa alguna de estas, esta preguntando por UN perfume puntual (el propio o
    // uno de otra marca que quiere que le busquemos parecido), no pidiendo opciones en general:
    // en ese caso alcanza con mostrarle el mejor match, no una lista de 3.
    private static final Set<String> REFERENCE_KEYWORDS = Set.of(
            "parecido", "parecida", "parecidos", "parecidas", "parece", "parecen", "similar", "similares",
            "inspiracion", "inspiraciones", "inspirado", "inspirada", "alternativa", "alternativas", "dupe", "dupes"
    );

    // Palabras muy comunes que no aportan ninguna pista real sobre lo que busca el cliente.
    private static final Set<String> STOPWORDS = Set.of(
            "un", "una", "unos", "unas", "el", "la", "los", "las", "de", "del", "para", "por",
            "que", "es", "como", "con", "me", "te", "se", "lo", "mi", "tu", "su", "y", "o",
            "algo", "alguna", "alguno", "hola", "buenas", "porfa", "porfavor", "gracias"
    );

    // Usado por RecommendationEngine para comparar la categoria que pidio el cliente contra las
    // categorias en texto libre que carga el admin (ej: "Dulce", "Especiado"), mapeando ambas al
    // mismo enum en vez de comparar las palabras exactas (que no siempre coinciden en genero/numero).
    public static Optional<PerfumeCategory> mapKeywordToCategory(String token) {
        return Optional.ofNullable(CATEGORY_KEYWORDS.get(token));
    }

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

        boolean productIntent = category != null
                || genderType != null
                || tokens.stream().anyMatch(INTENT_KEYWORDS::contains);

        // Si pidio perfumes pero no dio ninguna pista concreta (ni categoria, ni genero, ni
        // ninguna palabra "extra" mas alla del pedido generico), es mejor preguntarle algo
        // simple antes de recomendar cualquier cosa al voleo.
        boolean hasExtraSignal = tokens.stream().anyMatch(token ->
                token.length() > 2
                        && !INTENT_KEYWORDS.contains(token)
                        && !STOPWORDS.contains(token)
                        && !CATEGORY_KEYWORDS.containsKey(token)
                        && !GENDER_KEYWORDS.containsKey(token));

        boolean needsClarification = productIntent
                && category == null
                && genderType == null
                && !hasExtraSignal;

        boolean referencesSpecificPerfume = tokens.stream().anyMatch(REFERENCE_KEYWORDS::contains);

        return PreferenceCriteria.builder()
                .category(category)
                .genderType(genderType)
                .keywords(tokens)
                .productIntent(productIntent)
                .needsClarification(needsClarification)
                .referencesSpecificPerfume(referencesSpecificPerfume)
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
