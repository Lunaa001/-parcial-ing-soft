package com.iaperfumeadvisor.service.impl;

import com.iaperfumeadvisor.dto.request.admin.CreatePerfumeRequest;
import com.iaperfumeadvisor.dto.request.admin.UpdatePerfumeRequest;
import com.iaperfumeadvisor.dto.response.PerfumeResponse;
import com.iaperfumeadvisor.entity.Perfume;
import com.iaperfumeadvisor.enums.GenderType;
import com.iaperfumeadvisor.enums.PerfumeCategory;
import com.iaperfumeadvisor.enums.PerfumeStatus;
import com.iaperfumeadvisor.exception.InvalidInputException;
import com.iaperfumeadvisor.exception.ResourceNotFoundException;
import com.iaperfumeadvisor.mapper.PerfumeMapper;
import com.iaperfumeadvisor.repository.PerfumeRepository;
import com.iaperfumeadvisor.service.PerfumeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PerfumeServiceImpl implements PerfumeService {

    private final PerfumeRepository perfumeRepository;
    private final PerfumeMapper perfumeMapper;

    @Override
    public PerfumeResponse createPerfume(CreatePerfumeRequest request) {
        Perfume perfume = perfumeMapper.toEntity(request);
        Perfume saved = perfumeRepository.save(perfume);
        return perfumeMapper.toResponse(saved);
    }

    @Override
    public PerfumeResponse updatePerfume(Long id, UpdatePerfumeRequest request) {
        Perfume perfume = findPerfumeOrThrow(id);
        perfumeMapper.updateEntityFromRequest(request, perfume);
        Perfume updated = perfumeRepository.save(perfume);
        return perfumeMapper.toResponse(updated);
    }

    @Override
    public void deletePerfume(Long id) {
        Perfume perfume = findPerfumeOrThrow(id);
        perfumeRepository.delete(perfume);
    }

    @Override
    public PerfumeResponse getPerfumeById(Long id) {
        return perfumeMapper.toResponse(findPerfumeOrThrow(id));
    }

    @Override
    public List<PerfumeResponse> getAllPerfumes() {
        return toResponseList(perfumeRepository.findAll());
    }

    @Override
    public List<PerfumeResponse> getPerfumesByCategory(String category) {
        PerfumeCategory perfumeCategory = parseEnum(PerfumeCategory.class, category, "category");
        return toResponseList(perfumeRepository.findByCategory(perfumeCategory));
    }

    @Override
    public List<PerfumeResponse> getPerfumesByGender(String gender) {
        GenderType genderType = parseEnum(GenderType.class, gender, "gender");
        return toResponseList(perfumeRepository.findByGenderType(genderType));
    }

    @Override
    public List<PerfumeResponse> getAvailablePerfumes() {
        return toResponseList(perfumeRepository.findByStatusAndStockGreaterThan(PerfumeStatus.AVAILABLE, 0));
    }

    private Perfume findPerfumeOrThrow(Long id) {
        return perfumeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Perfume not found with id: " + id));
    }

    private List<PerfumeResponse> toResponseList(List<Perfume> perfumes) {
        return perfumes.stream()
                .map(perfumeMapper::toResponse)
                .collect(Collectors.toList());
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumType, String value, String fieldName) {
        try {
            return Enum.valueOf(enumType, value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new InvalidInputException("Invalid " + fieldName + ": " + value);
        }
    }
}
