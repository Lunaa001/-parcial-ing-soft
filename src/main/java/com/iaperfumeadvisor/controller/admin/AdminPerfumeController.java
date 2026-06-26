package com.iaperfumeadvisor.controller.admin;

import com.iaperfumeadvisor.dto.request.admin.CreatePerfumeRequest;
import com.iaperfumeadvisor.dto.request.admin.UpdatePerfumeRequest;
import com.iaperfumeadvisor.dto.response.PerfumeResponse;
import com.iaperfumeadvisor.service.PerfumeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/perfumes")
@RequiredArgsConstructor
public class AdminPerfumeController {

    private final PerfumeService perfumeService;

    @PostMapping
    public ResponseEntity<PerfumeResponse> createPerfume(@Valid @RequestBody CreatePerfumeRequest request) {
        PerfumeResponse response = perfumeService.createPerfume(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PerfumeResponse> updatePerfume(@PathVariable Long id,
                                                           @Valid @RequestBody UpdatePerfumeRequest request) {
        return ResponseEntity.ok(perfumeService.updatePerfume(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePerfume(@PathVariable Long id) {
        perfumeService.deletePerfume(id);
        return ResponseEntity.noContent().build();
    }
}
