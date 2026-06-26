package com.iaperfumeadvisor.service.impl;

import com.iaperfumeadvisor.dto.request.auth.RegisterRequest;
import com.iaperfumeadvisor.dto.response.UserResponse;
import com.iaperfumeadvisor.entity.User;
import com.iaperfumeadvisor.enums.Role;
import com.iaperfumeadvisor.exception.InvalidInputException;
import com.iaperfumeadvisor.exception.ResourceNotFoundException;
import com.iaperfumeadvisor.mapper.UserMapper;
import com.iaperfumeadvisor.repository.UserRepository;
import com.iaperfumeadvisor.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public User createUser(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new InvalidInputException("Username already exists: " + request.getUsername());
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new InvalidInputException("Email already exists: " + request.getEmail());
        }

        User user = userMapper.toEntity(request);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(Role.CLIENT);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    @Override
    public User getUserEntityByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }

    @Override
    public UserResponse getUserByUsername(String username) {
        return userMapper.toResponse(getUserEntityByUsername(username));
    }

    @Override
    public UserResponse getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
        return userMapper.toResponse(user);
    }
}
