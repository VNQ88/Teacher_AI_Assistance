package com.example.teacherassistantai.controller;


import com.example.teacherassistantai.common.response.PageResponse;
import com.example.teacherassistantai.common.response.ResponseData;
import com.example.teacherassistantai.dto.request.ChangePasswordRequest;
import com.example.teacherassistantai.dto.request.UpdateUserRequest;
import com.example.teacherassistantai.dto.response.UserResponse;
import com.example.teacherassistantai.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
@Validated
@Slf4j
@Tag(name = "User Controller")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @PreAuthorize("hasAuthority('ADMIN')")
    @Operation(summary = "Get user detail", description = "Send a request via this API to get user information")
    @GetMapping("/{userId}")
    public ResponseData<?> getUser(@PathVariable @Min(1) long userId) {
        log.info("Request get user detail, userId={}", userId);
        UserResponse user = userService.getUser(userId);
        return new ResponseData<>(HttpStatus.OK.value(), "user", user);
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @Operation(summary = "Get list of users per pageNo", description = "Send a request via this API to get user list by pageNo and pageSize")
    @GetMapping("/list")
    public ResponseData<?> getAllUsers(@RequestParam(defaultValue = "0", required = false) int pageNo,
                                       @Min(10) @RequestParam(defaultValue = "20", required = false) int pageSize) {
        log.info("Request get all users, pageNo={}, pageSize={}", pageNo, pageSize);
        PageResponse<?> users = userService.getAllUsers(pageNo, pageSize);
        return new ResponseData<>(HttpStatus.OK.value(), "users", users);

    }

    @GetMapping("/current")
    @Operation(summary = "Get current user information", description = "Send a request via this API to get current user information")
    public ResponseData<?> getCurrentUser() {
        log.info("Request get current user information");
        UserResponse user = userService.getCurrentUser();
        return new ResponseData<>(HttpStatus.OK.value(), "current user", user);
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change user password", description = "Send a request via this API to change user password")
    public ResponseData<?> changePassword(@RequestBody @Valid ChangePasswordRequest request) {
        log.info("Request change password for user");
        userService.changePassword(request);
        return new ResponseData<>(HttpStatus.OK.value(), "Change password successful");
    }

    // update user role to teacher (add role teacher)
    @PreAuthorize("hasAuthority('ADMIN')")
    @PostMapping("/{userId}/become-teacher")
    @Operation(summary = "Update user role to teacher", description = "Send a request via this API to update user role to teacher")
    public ResponseData<?> becomeTeacher(@PathVariable @Min(1) long userId) {
        log.info("Request update user role to teacher, userId={}", userId);
        userService.becomeTeacher(userId);
        return new ResponseData<>(HttpStatus.OK.value(), "Update user role to teacher successful");
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @PutMapping("/{userId}")
    @Operation(summary = "Update user information", description = "Send a request via this API to update user information")
    public ResponseData<?> updateUser(@PathVariable @Min(1) long userId,
                                      @RequestBody @Valid UpdateUserRequest request) {
        log.info("Request update user information, userId={}", userId);
        UserResponse user = userService.updateUser(userId, request);
        return new ResponseData<>(HttpStatus.OK.value(), "User updated successfully", user);
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @DeleteMapping("/{userId}")
    @Operation(summary = "Delete user", description = "Send a request via this API to delete a user")
    public ResponseData<?> deleteUser(@PathVariable @Min(1) long userId) {
        log.info("Request delete user, userId={}", userId);
        userService.deleteUser(userId);
        return new ResponseData<>(HttpStatus.OK.value(), "User deleted successfully");
    }
}
