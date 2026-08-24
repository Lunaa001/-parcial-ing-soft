package com.iaperfumeadvisor.mapper;

import com.iaperfumeadvisor.dto.request.auth.RegisterRequest;
import com.iaperfumeadvisor.entity.User;
import org.springframework.stereotype.Component;

// Arma la entidad User a partir del registro; la contraseña llega en texto plano aca y se
// encripta despues en UserServiceImpl.createUser antes de guardar.
@Component
public class UserMapper {

    public User toEntity(RegisterRequest request) {
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(request.getPassword());
        user.setFullName(request.getFullName());
        user.setEmail(request.getEmail());
        return user;
    }
}
