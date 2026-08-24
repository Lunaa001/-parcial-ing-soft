package com.iaperfumeadvisor.ai;

import com.iaperfumeadvisor.entity.Perfume;

// Par perfume + puntaje de afinidad calculado por RecommendationEngine; existe solo para poder
// ordenar los candidatos por que tan bien encajan antes de mapearlos a la respuesta final.
public record ScoredPerfume(Perfume perfume, double score) {
}
