package com.iaperfumeadvisor.ai;

import com.iaperfumeadvisor.entity.Perfume;
import com.iaperfumeadvisor.enums.GenderType;
import com.iaperfumeadvisor.enums.PerfumeStatus;
import com.iaperfumeadvisor.repository.PerfumeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RecommendationEngine {

    private final PerfumeRepository perfumeRepository;

    public List<ScoredPerfume> findMatches(PreferenceCriteria criteria) {
        List<Perfume> candidates = perfumeRepository.findByStatusAndStockGreaterThan(PerfumeStatus.AVAILABLE, 0);

        return candidates.stream()
                .filter(perfume -> matchesHardFilters(perfume, criteria))
                .map(perfume -> new ScoredPerfume(perfume, score(perfume, criteria)))
                .sorted(Comparator.comparingDouble(ScoredPerfume::score).reversed()
                        .thenComparing(sp -> Optional.ofNullable(sp.perfume().getRating()).orElse(0),
                                Comparator.reverseOrder()))
                .toList();
    }

    private boolean matchesHardFilters(Perfume perfume, PreferenceCriteria criteria) {
        if (criteria.getCategory() != null && perfume.getCategory() != criteria.getCategory()) {
            return false;
        }
        if (criteria.getGenderType() != null && !matchesGender(perfume.getGenderType(), criteria.getGenderType())) {
            return false;
        }
        if (criteria.getBrand() != null && !perfume.getBrand().equalsIgnoreCase(criteria.getBrand())) {
            return false;
        }
        if (criteria.getMinPrice() != null && perfume.getPrice().compareTo(criteria.getMinPrice()) < 0) {
            return false;
        }
        if (criteria.getMaxPrice() != null && perfume.getPrice().compareTo(criteria.getMaxPrice()) > 0) {
            return false;
        }
        if (criteria.getMinRating() != null
                && (perfume.getRating() == null || perfume.getRating() < criteria.getMinRating())) {
            return false;
        }
        return true;
    }

    private boolean matchesGender(GenderType perfumeGender, GenderType requestedGender) {
        return perfumeGender == requestedGender || perfumeGender == GenderType.UNISEX;
    }

    private double score(Perfume perfume, PreferenceCriteria criteria) {
        double score = 0;

        if (criteria.getCategory() != null && perfume.getCategory() == criteria.getCategory()) {
            score += 3;
        }
        if (criteria.getGenderType() != null && perfume.getGenderType() == criteria.getGenderType()) {
            score += 2;
        }
        if (criteria.getKeywords() != null) {
            String haystack = (perfume.getName() + " " + perfume.getBrand() + " "
                    + Optional.ofNullable(perfume.getDescription()).orElse(""))
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
}
