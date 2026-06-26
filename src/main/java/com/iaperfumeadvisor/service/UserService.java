package com.iaperfumeadvisor.service;

import com.iaperfumeadvisor.dto.request.auth.RegisterRequest;
import com.iaperfumeadvisor.dto.response.UserResponse;
import com.iaperfumeadvisor.entity.User;

public interface UserService {

    User createUser(RegisterRequest request);

    User getUserEntityByUsername(String username);

    UserResponse getUserByUsername(String username);

    UserResponse getUserById(Long id);
}
