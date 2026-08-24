package com.iaperfumeadvisor.entity;

import com.iaperfumeadvisor.enums.PerfumeStatus;
import com.iaperfumeadvisor.enums.GenderType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// Producto del catalogo tal como lo carga el admin; es la fuente de verdad que consume tanto la
// tienda (catalogo, carrito) como el motor de recomendaciones/IA para saber que ofrecer.
@Entity
@Table(name = "perfumes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Perfume {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Name is required")
    @Column(nullable = false, length = 255)
    private String name;

    @NotBlank(message = "Brand is required")
    @Column(nullable = false, length = 100)
    private String brand;

    @Column(columnDefinition = "TEXT")
    private String description;

    // Las 8 categorias fijas de PerfumeCategory se guardan aca por su nombre (ej: "FLORAL")
    // para que el motor de recomendaciones las siga reconociendo; el admin puede sumar
    // categorias propias en texto libre, que quedan solo para organizar el catalogo.
    @NotEmpty(message = "At least one category is required")
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "perfume_categories", joinColumns = @JoinColumn(name = "perfume_id"))
    @OrderColumn(name = "position")
    @Column(name = "category", nullable = false, length = 100)
    @Builder.Default
    private List<String> categories = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GenderType genderType;

    @NotNull(message = "Price is required")
    @Positive(message = "Price must be positive")
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @NotNull(message = "Stock is required")
    @Column(nullable = false)
    private Integer stock;

    @NotNull(message = "Status is required")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PerfumeStatus status;

    @Column(length = 500)
    private String imageUrl;

    private Integer rating;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
