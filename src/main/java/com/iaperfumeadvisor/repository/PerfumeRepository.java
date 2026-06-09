package com.iaperfumeadvisor.repository;

import com.iaperfumeadvisor.entity.Perfume;
import com.iaperfumeadvisor.enums.PerfumeCategory;
import com.iaperfumeadvisor.enums.GenderType;
import com.iaperfumeadvisor.enums.PerfumeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PerfumeRepository extends JpaRepository<Perfume, Long> {

    List<Perfume> findByStatus(PerfumeStatus status);

    List<Perfume> findByStockGreaterThan(Integer stock);

    List<Perfume> findByNameContainingIgnoreCase(String name);

    List<Perfume> findByCategory(PerfumeCategory category);

    List<Perfume> findByGenderType(GenderType genderType);

    List<Perfume> findByBrandContainingIgnoreCase(String brand);

    List<Perfume> findByStatusAndStockGreaterThan(PerfumeStatus status, Integer stock);

    List<Perfume> findByStatusAndCategory(PerfumeStatus status, PerfumeCategory category);
}
