package com.iaperfumeadvisor.enums;

// Solo AVAILABLE (con stock > 0) entra en las recomendaciones de la IA (ver RecommendationEngine);
// los demas valores son para que el admin organice el catalogo (que mostrar/ocultar en la tienda)
// sin que eso afecte la logica de recomendacion.
public enum PerfumeStatus {
    AVAILABLE,
    OUT_OF_STOCK,
    DISCONTINUED,
    COMING_SOON
}
