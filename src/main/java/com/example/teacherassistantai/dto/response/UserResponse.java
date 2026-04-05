package com.example.teacherassistantai.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Set;

@Setter
@Getter
@Builder
public class UserResponse implements Serializable {
    private Long id;
    private String email;
    private String fullName;
    private String avatar;
    private Boolean enabled;
    private Set<String> roles;
}
