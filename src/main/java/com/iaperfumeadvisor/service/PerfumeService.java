package com.iaperfumeadvisor.service;

import com.iaperfumeadvisor.dto.request.admin.CreatePerfumeRequest;
import com.iaperfumeadvisor.dto.request.admin.UpdatePerfumeRequest;
import com.iaperfumeadvisor.dto.response.PerfumeResponse;
import java.util.List;

public interface PerfumeService {

    PerfumeResponse createPerfume(CreatePerfumeRequest request);

    PerfumeResponse updatePerfume(Long id, UpdatePerfumeRequest request);

    void deletePerfume(Long id);

    PerfumeResponse getPerfumeById(Long id);

    List<PerfumeResponse> getAllPerfumes();

    List<PerfumeResponse> getPerfumesByCategory(String category);

    List<PerfumeResponse> getPerfumesByGender(String gender);

    List<PerfumeResponse> getAvailablePerfumes();
}
