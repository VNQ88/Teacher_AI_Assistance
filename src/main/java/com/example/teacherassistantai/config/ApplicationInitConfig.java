package com.example.teacherassistantai.config;

import com.example.teacherassistantai.entity.Role;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.repository.RoleRepository;
import com.example.teacherassistantai.repository.UserRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.HashSet;

@EnableAsync
@Configuration
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
public class ApplicationInitConfig {
    RoleRepository roleRepository;
    @Bean
    @ConditionalOnProperty(
            prefix = "spring",
            value = "datasource.driverClassName",
            havingValue = "com.postgresql.jdbc.Driver",
            matchIfMissing = true)
    ApplicationRunner applicationRunner(UserRepository userRepository) {
        log.info("Init application .....");
        return args -> {

            if ((roleRepository.findByName("ADMIN").isEmpty())) {
                Role role = Role.builder().name("ADMIN").build();
                roleRepository.save(role);
                log.info("Role 'ADMIN' has been created");
            }

            if ((roleRepository.findByName("TEACHER").isEmpty())) {
                Role role = Role.builder().name("TEACHER").build();
                roleRepository.save(role);
                log.info("Role 'TEACHER' has been created");
            }

            if ((roleRepository.findByName("STUDENT").isEmpty())) {
                Role role = Role.builder().name("STUDENT").build();
                roleRepository.save(role);
                log.info("Role 'STUDENT' has been created");
            }

            if (Boolean.FALSE.equals(userRepository.existsByEmail("admin@example.com"))) {
                HashSet<Role> roles = new HashSet<>();
                roleRepository.findByName("ADMIN").ifPresent(roles::add);
                User user = User.builder()
                        .email("admin@example.com")
                        .password("admin123")
                        .fullName("ADMIN")
                        .enabled(true)
                        .roles(roles)
                        .build();
                userRepository.save(user);
                log.warn("admin user has been created with default email: admin@example.com password: admin123, please change it");
            }

            if (Boolean.FALSE.equals(userRepository.existsByEmail("teacher@example.com"))) {
                HashSet<Role> roles = new HashSet<>();
                roleRepository.findByName("TEACHER").ifPresent(roles::add);
                User user = User.builder()
                        .email("teacher@example.com")
                        .password("teacher123")
                        .fullName("TEACHER EXAMPLE 1")
                        .enabled(true)
                        .roles(roles)
                        .build();
                userRepository.save(user);
                log.warn("teacher user has been created with default email: teacher@example.com password: teacher123, please change it");
            }

            if (Boolean.FALSE.equals(userRepository.existsByEmail("student@example.com"))) {
                HashSet<Role> roles = new HashSet<>();
                roleRepository.findByName("STUDENT").ifPresent(roles::add);
                User user = User.builder()
                        .email("student@example.com")
                        .password("student123")
                        .fullName("STUDENT EXAMPLE 1")
                        .enabled(true)
                        .roles(roles)
                        .build();
                userRepository.save(user);
                log.warn("student user has been created with default email: student@example.com password: student123, please change it");
            }
        };
    }
}
