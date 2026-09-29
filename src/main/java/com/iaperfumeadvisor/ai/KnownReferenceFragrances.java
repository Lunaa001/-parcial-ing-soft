package com.iaperfumeadvisor.ai;

import com.iaperfumeadvisor.enums.GenderType;
import com.iaperfumeadvisor.enums.PerfumeCategory;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// Perfumes de otras marcas que los clientes preguntan seguido ("parecido a...", "alternativa a...",
// "dupe de...") con su familia olfativa y genero real (clasificacion de industria ampliamente
// documentada, no opinion de foro). Sin esto, interpretar esos pedidos depende 100% de lo que la IA
// "recuerda" de memoria en cada llamada, que puede variar de una vez a otra (ver GroqServiceImpl);
// esta lista le da una respuesta fija y confiable para los casos mas comunes, sin salir a internet.
@Component
public class KnownReferenceFragrances {

    public record Match(PerfumeCategory category, GenderType genderType) {
    }

    private record Entry(Set<String> matchTokens, PerfumeCategory category, GenderType genderType) {
    }

    private static final List<Entry> ENTRIES = List.of(
            new Entry(Set.of("sauvage"), PerfumeCategory.AROMATIC, GenderType.MALE),
            new Entry(Set.of("tobacco", "vanille"), PerfumeCategory.ORIENTAL, GenderType.UNISEX),
            new Entry(Set.of("aventus"), PerfumeCategory.CHYPRE, GenderType.MALE),
            new Entry(Set.of("libre"), PerfumeCategory.ORIENTAL, GenderType.FEMALE),
            new Entry(Set.of("light", "blue"), PerfumeCategory.CITRUS, GenderType.FEMALE),
            new Entry(Set.of("good", "girl"), PerfumeCategory.ORIENTAL, GenderType.FEMALE),
            new Entry(Set.of("vie", "belle"), PerfumeCategory.ORIENTAL, GenderType.FEMALE),
            new Entry(Set.of("black", "opium"), PerfumeCategory.ORIENTAL, GenderType.FEMALE),
            new Entry(Set.of("club", "nuit"), PerfumeCategory.CHYPRE, GenderType.UNISEX),
            new Entry(Set.of("baccarat", "rouge"), PerfumeCategory.ORIENTAL, GenderType.UNISEX),
            new Entry(Set.of("million"), PerfumeCategory.ORIENTAL, GenderType.MALE),
            new Entry(Set.of("bleu", "chanel"), PerfumeCategory.AROMATIC, GenderType.MALE),
            new Entry(Set.of("eros"), PerfumeCategory.AROMATIC, GenderType.MALE),
            new Entry(Set.of("acqua", "gio"), PerfumeCategory.FRESH, GenderType.MALE),
            new Entry(Set.of("flowerbomb"), PerfumeCategory.FLORAL, GenderType.FEMALE),
            new Entry(Set.of("mademoiselle"), PerfumeCategory.CHYPRE, GenderType.FEMALE),
            new Entry(Set.of("adore"), PerfumeCategory.FLORAL, GenderType.FEMALE)
    );

    // Busca el primer perfume de referencia cuyas palabras clave esten TODAS presentes en el
    // mensaje (sin importar mayusculas/acentos/orden), igual que el resto del matcheo por tokens.
    public Optional<Match> match(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        Set<String> tokens = Set.copyOf(tokenize(message));
        return ENTRIES.stream()
                .filter(entry -> tokens.containsAll(entry.matchTokens()))
                .findFirst()
                .map(entry -> new Match(entry.category(), entry.genderType()));
    }

    private List<String> tokenize(String message) {
        String normalized = Normalizer.normalize(message.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return Arrays.stream(normalized.split("[^a-z0-9]+"))
                .filter(token -> !token.isBlank())
                .toList();
    }
}
