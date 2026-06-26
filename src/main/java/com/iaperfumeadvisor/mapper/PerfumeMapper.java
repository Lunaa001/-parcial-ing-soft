package com.iaperfumeadvisor.mapper;

import com.iaperfumeadvisor.dto.request.admin.CreatePerfumeRequest;
import com.iaperfumeadvisor.dto.request.admin.UpdatePerfumeRequest;
import com.iaperfumeadvisor.dto.response.PerfumeResponse;
import com.iaperfumeadvisor.entity.Perfume;
import org.springframework.stereotype.Component;

@Component
public class PerfumeMapper {

    public Perfume toEntity(CreatePerfumeRequest request) {
        return Perfume.builder()
                .name(request.getName())
                .brand(request.getBrand())
                .description(request.getDescription())
                .category(request.getCategory())
                .genderType(request.getGenderType())
                .price(request.getPrice())
                .stock(request.getStock())
                .status(request.getStatus())
                .imageUrl(request.getImageUrl())
                .build();
    }

    public void updateEntityFromRequest(UpdatePerfumeRequest request, Perfume perfume) {
        if (request.getName() != null) {
            perfume.setName(request.getName());
        }
        if (request.getBrand() != null) {
            perfume.setBrand(request.getBrand());
        }
        if (request.getDescription() != null) {
            perfume.setDescription(request.getDescription());
        }
        if (request.getCategory() != null) {
            perfume.setCategory(request.getCategory());
        }
        if (request.getGenderType() != null) {
            perfume.setGenderType(request.getGenderType());
        }
        if (request.getPrice() != null) {
            perfume.setPrice(request.getPrice());
        }
        if (request.getStock() != null) {
            perfume.setStock(request.getStock());
        }
        if (request.getStatus() != null) {
            perfume.setStatus(request.getStatus());
        }
        if (request.getImageUrl() != null) {
            perfume.setImageUrl(request.getImageUrl());
        }
    }

    public PerfumeResponse toResponse(Perfume perfume) {
        return PerfumeResponse.builder()
                .id(perfume.getId())
                .name(perfume.getName())
                .brand(perfume.getBrand())
                .description(perfume.getDescription())
                .category(perfume.getCategory().toString())
                .genderType(perfume.getGenderType().toString())
                .price(perfume.getPrice())
                .stock(perfume.getStock())
                .status(perfume.getStatus().toString())
                .imageUrl(perfume.getImageUrl())
                .rating(perfume.getRating())
                .build();
    }
}
