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

    List<Perfume> findByStatus(PerfumeStatus status);

    List<Perfume> findByStockGreaterThan(Integer stock);

    List<Perfume> findByNameContainingIgnoreCase(String name);

    @Query("SELECT DISTINCT p FROM Perfume p JOIN p.categories c WHERE c = :category")
    List<Perfume> findByCategory(@Param("category") String category);

    List<Perfume> findByGenderType(GenderType genderType);

    List<Perfume> findByBrandContainingIgnoreCase(String brand);

    List<Perfume> findByStatusAndStockGreaterThan(PerfumeStatus status, Integer stock);

    @Query("SELECT DISTINCT p FROM Perfume p JOIN p.categories c WHERE p.status = :status AND c = :category")
    List<Perfume> findByStatusAndCategory(@Param("status") PerfumeStatus status, @Param("category") String category);
}
