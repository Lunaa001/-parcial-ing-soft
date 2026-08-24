package com.iaperfumeadvisor.service.impl;

import com.iaperfumeadvisor.exception.BusinessException;
import com.iaperfumeadvisor.exception.InvalidInputException;
import com.iaperfumeadvisor.service.ImageStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

// Guarda las fotos de producto en disco local (no en un bucket externo): alcanza para el
// tamaño de catalogo actual y evita depender de un servicio de almacenamiento pago.
@Service
public class ImageStorageServiceImpl implements ImageStorageService {

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    @Override
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidInputException("El archivo esta vacio");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new InvalidInputException("El archivo debe ser una imagen (jpg, png, webp o gif)");
        }

        try {
            Path perfumesDir = Path.of(uploadDir, "perfumes");
            Files.createDirectories(perfumesDir);

            // Nombre random en vez del nombre original: evita colisiones entre archivos y que
            // alguien adivine/pisen rutas de otras fotos ya subidas.
            String filename = UUID.randomUUID() + extensionFor(contentType);
            Path destination = perfumesDir.resolve(filename);

            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, destination);
            }

            return buildPublicUrl("/uploads/perfumes/" + filename);
        } catch (IOException ex) {
            throw new BusinessException("No se pudo guardar la imagen", ex);
        }
    }

    private String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }

    // Arma la URL absoluta usando el host con el que el cliente nos contacto (localhost,
    // IP de la red local, etc.), para que la foto se pueda ver desde cualquier dispositivo.
    private String buildPublicUrl(String relativePath) {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return relativePath;
        }
        HttpServletRequest request = attrs.getRequest();
        return request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + relativePath;
    }
}
