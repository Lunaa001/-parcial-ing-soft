package com.iaperfumeadvisor.ai;

import com.iaperfumeadvisor.entity.Perfume;
import com.iaperfumeadvisor.enums.GenderType;
import com.iaperfumeadvisor.enums.PerfumeStatus;
import com.iaperfumeadvisor.repository.PerfumeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// Segundo paso del pipeline: toma los criterios de PreferenceAnalyzer y los cruza contra el
// catalogo real (stock disponible) para decidir que productos concretos ofrecer, con un
// puntaje de afinidad. Es puramente algoritmico (sin IA); el texto final para el cliente lo
// arma despues PromptBuilder/GroqService a partir de estos matches.
@Component
@RequiredArgsConstructor
public class RecommendationEngine {

    // No hace falta abrumar al cliente: mostramos solo los que mejor encajan.
    private static final int MAX_RECOMMENDATIONS = 3;

    private final PerfumeRepository perfumeRepository;

    public List<ScoredPerfume> findMatches(PreferenceCriteria criteria) {
        List<Perfume> candidates = perfumeRepository.findByStatusAndStockGreaterThan(PerfumeStatus.AVAILABLE, 0);

        // Si el cliente escribio el nombre de un producto nuestro directamente (ej: "Yara Pink?"),
        // eso cuenta como intencion de compra aunque no haya ninguna palabra clave de las de abajo:
        // sin este chequeo, una pregunta asi caia en charla general y la IA respondia sin ningun
        // dato real del producto (precio, stock, etc).
        Optional<Perfume> nameMatch = findByExactName(candidates, criteria.getKeywords());
        if (nameMatch.isPresent()) {
            return List.of(new ScoredPerfume(nameMatch.get(), 999.0));
        }

        if (!criteria.isProductIntent() || criteria.isNeedsClarification()) {
            return List.of();
        }

        // "Parecido a/inspiracion de" un perfume puntual: alcanza con el que mejor encaje, no
        // con una lista de opciones (eso es para pedidos generales tipo "quiero algo dulce").
        int limit = criteria.isReferencesSpecificPerfume() ? 1 : MAX_RECOMMENDATIONS;

        return candidates.stream()
                .filter(perfume -> matchesHardFilters(perfume, criteria))
                .map(perfume -> new ScoredPerfume(perfume, score(perfume, criteria)))
                .sorted(Comparator.comparingDouble(ScoredPerfume::score).reversed()
                        .thenComparing(sp -> Optional.ofNullable(sp.perfume().getRating()).orElse(0),
                                Comparator.reverseOrder()))
                .limit(limit)
                .toList();
    }

    private boolean matchesHardFilters(Perfume perfume, PreferenceCriteria criteria) {
        if (criteria.getCategory() != null && !matchesCategory(perfume, criteria)) {
            return false;
        }
        if (criteria.getGenderType() != null && !matchesGender(perfume.getGenderType(), criteria.getGenderType())) {
            return false;
        }
        return true;
    }

    // El admin puede cargar categorias fijas (ej: "FLORAL") o propias en texto libre (ej: "Dulce",
    // "Especiado"). Para estas ultimas, mapeamos cada palabra de esa categoria al mismo enum que usa
    // PreferenceAnalyzer (en vez de buscarla tal cual entre las palabras del cliente): asi "dulce" y
    // "dulces" en el catalogo matchean igual sin importar el genero/numero que haya usado el cliente.
    private boolean matchesCategory(Perfume perfume, PreferenceCriteria criteria) {
        String enumName = criteria.getCategory().name();
        for (String category : perfume.getCategories()) {
            if (category.equalsIgnoreCase(enumName)) {
                return true;
            }
            for (String token : tokenizeName(category)) {
                if (criteria.getCategory().equals(PreferenceAnalyzer.mapKeywordToCategory(token).orElse(null))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesGender(GenderType perfumeGender, GenderType requestedGender) {
        return perfumeGender == requestedGender || perfumeGender == GenderType.UNISEX;
    }

    private double score(Perfume perfume, PreferenceCriteria criteria) {
        double score = 0;

        if (criteria.getCategory() != null && matchesCategory(perfume, criteria)) {
            score += 3;
        }
        if (criteria.getGenderType() != null && perfume.getGenderType() == criteria.getGenderType()) {
            score += 2;
        }
        if (criteria.getKeywords() != null) {
            String haystack = (perfume.getName() + " " + perfume.getBrand() + " "
                    + Optional.ofNullable(perfume.getDescription()).orElse("") + " "
                    + String.join(" ", perfume.getCategories()))
                    .toLowerCase();
            for (String keyword : criteria.getKeywords()) {
                if (keyword.length() > 2 && haystack.contains(keyword)) {
                    score += 1;
                }
            }
        }
        if (perfume.getRating() != null) {
            score += perfume.getRating() / 5.0;
        }

        return score;
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.trim();
    }

    // Busca un perfume cuyo nombre este completo (todas sus palabras) dentro de lo que escribio
    // el cliente, sin importar el orden ni mayusculas/acentos. Asi "Yara pink?" matchea "Yara Pink".
    private Optional<Perfume> findByExactName(List<Perfume> candidates, List<String> messageTokens) {
        if (messageTokens == null || messageTokens.isEmpty()) {
            return Optional.empty();
        }
        Set<String> tokenSet = new HashSet<>(messageTokens);
        return candidates.stream()
                .filter(perfume -> {
                    List<String> nameTokens = tokenizeName(perfume.getName());
                    return !nameTokens.isEmpty() && tokenSet.containsAll(nameTokens);
                })
                .findFirst();
    }

    private List<String> tokenizeName(String name) {
        return Arrays.stream(normalize(name).split("[^a-z0-9]+"))
                .filter(token -> token.length() > 1)
                .toList();
    }
}
