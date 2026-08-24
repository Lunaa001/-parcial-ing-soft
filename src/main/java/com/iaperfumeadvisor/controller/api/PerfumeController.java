package com.iaperfumeadvisor.controller.api;

import com.iaperfumeadvisor.dto.response.PerfumeResponse;
import com.iaperfumeadvisor.service.PerfumeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Consulta publica de catalogo (sin login) para la tienda: listar, ver detalle y filtrar por
// categoria/genero/disponibilidad. La gestion (alta/edicion/borrado) es de AdminPerfumeController.
@RestController
@RequestMapping("/api/perfumes")
@RequiredArgsConstructor
public class PerfumeController {

    private final PerfumeService perfumeService;

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

    @GetMapping("/gender/{gender}")
    public ResponseEntity<List<PerfumeResponse>> getPerfumesByGender(@PathVariable String gender) {
        return ResponseEntity.ok(perfumeService.getPerfumesByGender(gender));
    }

    @GetMapping("/available")
    public ResponseEntity<List<PerfumeResponse>> getAvailablePerfumes() {
        return ResponseEntity.ok(perfumeService.getAvailablePerfumes());
    }
}
