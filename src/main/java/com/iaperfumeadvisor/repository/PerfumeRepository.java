package com.iaperfumeadvisor.repository;

import com.iaperfumeadvisor.entity.Perfume;
import com.iaperfumeadvisor.enums.GenderType;
import com.iaperfumeadvisor.enums.PerfumeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PerfumeRepository extends JpaRepository<Perfume, Long> {

    @Query("SELECT DISTINCT p FROM Perfume p JOIN p.categories c WHERE c = :category")
    List<Perfume> findByCategory(@Param("category") String category);

    List<Perfume> findByGenderType(GenderType genderType);

    List<Perfume> findByStatusAndStockGreaterThan(PerfumeStatus status, Integer stock);
}
