package com.iaperfumeadvisor.service;

import org.springframework.web.multipart.MultipartFile;

public interface ImageStorageService {

    String store(MultipartFile file);
}
