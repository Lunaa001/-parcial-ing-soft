package com.iaperfumeadvisor.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestTraceFilterTest {

    @Test
    void sanitizeReemplazaSaltosDeLineaYCaracteresDeControl() {
        assertEquals("/api/perfumes__INFO falso", RequestTraceFilter.sanitize("/api/perfumes\r\nINFO falso"));
        assertEquals("GET_x", RequestTraceFilter.sanitize("GET\tx"));
        assertEquals("/a_b", RequestTraceFilter.sanitize("/a\u0000b"));
    }

    @Test
    void sanitizeDejaIgualLasUrlsNormales() {
        assertEquals("/api/perfumes/category/CITRUS?x=1%0A", RequestTraceFilter.sanitize("/api/perfumes/category/CITRUS?x=1%0A"));
        assertEquals("", RequestTraceFilter.sanitize(null));
    }
}
