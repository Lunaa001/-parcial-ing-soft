package com.iaperfumeadvisor.service;

import com.iaperfumeadvisor.dto.request.auth.RegisterRequest;
import com.iaperfumeadvisor.entity.User;

public interface UserService {

    User createUser(RegisterRequest request);

    User getUserEntityByUsername(String username);
}
