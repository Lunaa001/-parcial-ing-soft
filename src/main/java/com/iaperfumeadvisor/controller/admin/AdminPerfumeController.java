package com.iaperfumeadvisor.controller.admin;

import com.iaperfumeadvisor.dto.request.admin.CreatePerfumeRequest;
import com.iaperfumeadvisor.dto.request.admin.UpdatePerfumeRequest;
import com.iaperfumeadvisor.dto.response.ImageUploadResponse;
import com.iaperfumeadvisor.dto.response.PerfumeResponse;
import com.iaperfumeadvisor.service.ImageStorageService;
import com.iaperfumeadvisor.service.PerfumeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// Gestion del catalogo reservada al rol ADMIN (ver SecurityConfig): alta, edicion, borrado y
// carga de fotos de producto. La consulta publica del catalogo vive aparte, en PerfumeController.
@RestController
@RequestMapping("/api/admin/perfumes")
@RequiredArgsConstructor
public class AdminPerfumeController {

    private final PerfumeService perfumeService;
    private final ImageStorageService imageStorageService;

    @PostMapping
    public ResponseEntity<PerfumeResponse> createPerfume(@Valid @RequestBody CreatePerfumeRequest request) {
        PerfumeResponse response = perfumeService.createPerfume(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageUploadResponse> uploadImage(@RequestParam("file") MultipartFile file) {
        String url = imageStorageService.store(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(ImageUploadResponse.builder().url(url).build());
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
