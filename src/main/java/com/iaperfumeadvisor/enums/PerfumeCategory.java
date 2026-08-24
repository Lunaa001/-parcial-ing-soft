package com.iaperfumeadvisor.enums;

// Categorias fijas que entiende el motor de recomendaciones (ver PreferenceAnalyzer). El admin
// puede ademas cargar categorias propias en texto libre en Perfume.categories; esas se mapean a
// estos mismos valores por palabra clave, no se comparan literalmente.
public enum PerfumeCategory {
    FLORAL,
    FRUITY,
    ORIENTAL,
    WOODY,
    FRESH,
    CHYPRE,
    AROMATIC,
    CITRUS
}
