package com.iaperfumeadvisor.controller.api;

import com.iaperfumeadvisor.dto.response.PerfumeResponse;
import com.iaperfumeadvisor.service.PerfumeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/public/perfumes")
public class PerfumeController {

    @Autowired
    private PerfumeService perfumeService;

    @GetMapping
    public ResponseEntity<List<PerfumeResponse>> getAllPerfumes() {
        return ResponseEntity.ok(perfumeService.getAllPerfumes());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PerfumeResponse> getPerfumeById(@PathVariable Long id) {
        return ResponseEntity.ok(perfumeService.getPerfumeById(id));
    }

    @GetMapping("/category/{category}")
    public ResponseEntity<List<PerfumeResponse>> getPerfumesByCategory(@PathVariable String category) {
        return ResponseEntity.ok(perfumeService.getPerfumesByCategory(category));
    }
}
