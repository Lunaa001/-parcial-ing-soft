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

// Crea el usuario ADMIN por defecto al arrancar, si todavia no existe: es la unica forma de
// tener un admin, ya que el registro publico (UserServiceImpl.createUser) siempre da rol CLIENT.
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

    // Si no se configuro una password, preferimos no crear el admin (con password vacia/adivinable)
    // antes que dejar una cuenta insegura dando vueltas.
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
