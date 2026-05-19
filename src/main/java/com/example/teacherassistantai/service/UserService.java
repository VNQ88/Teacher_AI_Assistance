package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.response.PageResponse;
import com.example.teacherassistantai.dto.request.ChangePasswordRequest;
import com.example.teacherassistantai.dto.request.CreateUserRequest;
import com.example.teacherassistantai.dto.request.UpdateUserRequest;
import com.example.teacherassistantai.dto.response.UserResponse;
import com.example.teacherassistantai.entity.Role;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.AccessDeniedOperationException;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.mapper.UserMapper;
import com.example.teacherassistantai.repository.RoleRepository;
import com.example.teacherassistantai.repository.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
@Service
public class UserService {
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final RoleRepository roleRepository;

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new InvalidDataException("Email already exists: " + request.getEmail());
        }
        Role role = roleRepository.findByName(request.getRole())
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + request.getRole()));
        User user = User.builder()
                .email(request.getEmail())
                .fullName(request.getFullName().trim())
                .password(request.getPassword())
                .enabled(true)
                .roles(Set.of(role))
                .build();
        return userMapper.toUserResponse(userRepository.save(user));
    }

    public PageResponse<?> getAllUsers(int pageNo, @Min(10) int pageSize) {
        Page<User> userPage = userRepository.findAll(PageRequest.of(pageNo, pageSize));
        List<UserResponse> userResponses = userPage.getContent().stream()
                .map(userMapper::toUserResponse)
                .toList();
        return PageResponse.builder()
                .pageNo(pageNo)
                .pageSize(pageSize)
                .totalPage(userPage.getTotalPages())
                .items(userResponses)
                .build();
    }

    public UserResponse getUser(@Min(1) long userId) {
        User user = getUserById(userId);
        return userMapper.toUserResponse(user);
    }

    public UserResponse getUserByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));
        return userMapper.toUserResponse(user);
    }

    private User getUserById(long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException(
                "User not found with id: " + userId));
    }

    public UserResponse getCurrentUser() {
        return userMapper.toUserResponse(getCurrentUserEntity());
    }

    public void changePassword(@Valid ChangePasswordRequest request) {
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("New password and confirm new password do not match");
        }

        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + userEmail));
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Old password is incorrect");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }

    public void becomeTeacher(@Min(1) long userId) {
        User user = getUserById(userId);
        Role teacherRole = roleRepository.findByName("TEACHER")
                .orElseThrow(() -> new ResourceNotFoundException("Role TEACHER not found"));
        if (user.getRoles().contains(teacherRole)) {
            throw new IllegalArgumentException("User is already a teacher");
        }

        user.getRoles().add(teacherRole);
        userRepository.save(user);
    }

    @Transactional
    public UserResponse updateUser(@Min(1) long userId, @Valid UpdateUserRequest request) {
        validateUpdatePermission(userId);

        User user = getUserById(userId);

        // Check if email is being changed and if new email already exists
        if (request.getEmail() != null && !request.getEmail().equals(user.getEmail())) {
            if (userRepository.existsByEmail(request.getEmail())) {
                throw new InvalidDataException("Email already exists: " + request.getEmail());
            }
            user.setEmail(request.getEmail());
        }

        if (request.getFullName() != null) {
            user.setFullName(request.getFullName().trim());
        }

        if (request.getAvatar() != null) {
            user.setAvatar(request.getAvatar());
        }

        return userMapper.toUserResponse(userRepository.save(user));
    }

    private void validateUpdatePermission(long userId) {
        User currentUser = getCurrentUserEntity();
        boolean isAdmin = currentUser.getRoles().stream()
                .map(Role::getName)
                .anyMatch("ADMIN"::equalsIgnoreCase);
        boolean isSelf = currentUser.getId() != null && currentUser.getId().equals(userId);

        if (!isAdmin && !isSelf) {
            throw new AccessDeniedOperationException("You don't have permission to update this user");
        }
    }

    private User getCurrentUserEntity() {
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Current user not found"));
    }

    @Transactional
    public void deleteUser(@Min(1) long userId) {
        User user = getUserById(userId);

        // Prevent deleting current logged-in user
        String currentUserEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        if (user.getEmail().equals(currentUserEmail)) {
            throw new InvalidDataException("Cannot delete currently logged-in user");
        }

        userRepository.delete(user);
    }
}
