package com.iaperfumeadvisor.ai;

import com.iaperfumeadvisor.enums.GenderType;
import com.iaperfumeadvisor.enums.PerfumeCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

// Resultado de PreferenceAnalyzer: lo que se pudo inferir del mensaje del cliente antes de
// tocar el catalogo o la IA. RecommendationEngine consume esto para decidir que perfumes
// mostrar (o si corresponde pedir mas info en vez de recomendar al voleo).
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PreferenceCriteria {
    private PerfumeCategory category;
    private GenderType genderType;
    private List<String> keywords;
    // Si el mensaje realmente pide perfumes/recomendaciones (vs. saludos, agradecimientos, charla general).
    private boolean productIntent;
    // Si pidio perfumes pero sin ninguna pista concreta: conviene preguntar antes de recomendar.
    private boolean needsClarification;
    // Si pregunto por un perfume puntual (el nuestro o "algo parecido/inspirado en" otro):
    // ahi alcanza con el mejor match, no con una lista de opciones.
    private boolean referencesSpecificPerfume;
}
