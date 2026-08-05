package com.iaperfumeadvisor.config;

import com.iaperfumeadvisor.entity.User;
import com.iaperfumeadvisor.enums.Role;
import com.iaperfumeadvisor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminUserInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.default.username:admin}")
    private String defaultUsername;

    @Value("${admin.default.password:}")
    private String defaultPassword;

    @Value("${admin.default.email:admin@iaperfumeadvisor.com}")
    private String defaultEmail;

    @Override
    public void run(String... args) {
        if (defaultPassword == null || defaultPassword.isBlank()) {
            log.warn("admin.default.password no esta configurada: se omite la creacion del admin por defecto");
            return;
        }
        if (userRepository.existsByUsername(defaultUsername)) {
            return;
        }

        User admin = User.builder()
                .username(defaultUsername)
                .password(passwordEncoder.encode(defaultPassword))
                .fullName("Administrator")
                .email(defaultEmail)
                .role(Role.ADMIN)
                .enabled(true)
                .build();
        userRepository.save(admin);
        log.info("Usuario ADMIN por defecto creado: {}", defaultUsername);
    }
}
