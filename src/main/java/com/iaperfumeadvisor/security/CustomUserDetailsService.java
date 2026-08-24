package com.iaperfumeadvisor.security;

import com.iaperfumeadvisor.entity.User;
import com.iaperfumeadvisor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

// Puente entre nuestra entidad User y el UserDetails que espera Spring Security (login y
// autorizacion por rol via AuthenticationManager/JwtAuthenticationFilter).
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPassword())
                // El prefijo "ROLE_" es una convencion que exige Spring Security para que
                // hasRole("ADMIN") (sin el prefijo) matchee esta autoridad.
                .authorities(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
                .disabled(!user.isEnabled())
                .build();
    }
}
