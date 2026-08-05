package com.iaperfumeadvisor.ai;

import com.iaperfumeadvisor.entity.Perfume;

public record ScoredPerfume(Perfume perfume, double score) {
}
