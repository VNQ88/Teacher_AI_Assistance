package com.example.teacherassistantai.service;

import com.example.teacherassistantai.dto.request.UpdateUserRequest;
import com.example.teacherassistantai.dto.response.UserResponse;
import com.example.teacherassistantai.entity.Role;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.AccessDeniedOperationException;
import com.example.teacherassistantai.mapper.UserMapper;
import com.example.teacherassistantai.repository.RoleRepository;
import com.example.teacherassistantai.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceUpdatePermissionTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private RoleRepository roleRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, userMapper, passwordEncoder, roleRepository);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void updateUser_shouldAllowAdminToUpdateAnotherUser() {
        User admin = user(1L, "admin@mail.com", "ADMIN");
        User target = user(2L, "target@mail.com", "STUDENT");
        UpdateUserRequest request = request("Updated Name");
        UserResponse response = UserResponse.builder().id(target.getId()).fullName("Updated Name").build();

        authenticateAs(admin.getEmail());
        when(userRepository.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(userRepository.save(target)).thenReturn(target);
        when(userMapper.toUserResponse(target)).thenReturn(response);

        UserResponse result = userService.updateUser(target.getId(), request);

        assertEquals(response, result);
        assertEquals("Updated Name", target.getFullName());
        verify(userRepository).save(target);
    }

    @Test
    void updateUser_shouldAllowUserToUpdateSelf() {
        User currentUser = user(5L, "self@mail.com", "STUDENT");
        UpdateUserRequest request = request("Self Updated");
        UserResponse response = UserResponse.builder().id(currentUser.getId()).fullName("Self Updated").build();

        authenticateAs(currentUser.getEmail());
        when(userRepository.findByEmail(currentUser.getEmail())).thenReturn(Optional.of(currentUser));
        when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
        when(userRepository.save(currentUser)).thenReturn(currentUser);
        when(userMapper.toUserResponse(currentUser)).thenReturn(response);

        UserResponse result = userService.updateUser(currentUser.getId(), request);

        assertEquals(response, result);
        assertEquals("Self Updated", currentUser.getFullName());
        verify(userRepository).save(currentUser);
    }

    @Test
    void updateUser_shouldDenyUserUpdatingAnotherUser() {
        User currentUser = user(3L, "other@mail.com", "STUDENT");

        authenticateAs(currentUser.getEmail());
        when(userRepository.findByEmail(currentUser.getEmail())).thenReturn(Optional.of(currentUser));

        assertThrows(AccessDeniedOperationException.class, () -> userService.updateUser(4L, request("Blocked")));

        verify(userRepository, never()).findById(4L);
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(User.class));
    }

    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, "n/a", AuthorityUtils.NO_AUTHORITIES)
        );
    }

    private UpdateUserRequest request(String fullName) {
        UpdateUserRequest request = new UpdateUserRequest();
        request.setFullName(fullName);
        return request;
    }

    private User user(Long id, String email, String roleName) {
        User user = User.builder()
                .email(email)
                .fullName(email)
                .password("x")
                .enabled(true)
                .roles(Set.of(Role.builder().name(roleName).build()))
                .build();
        user.setId(id);
        return user;
    }
}
