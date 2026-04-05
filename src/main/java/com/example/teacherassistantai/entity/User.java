package com.example.teacherassistantai.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.security.Principal;
import java.util.Collection;
import java.util.Set;

@Getter
@Setter
@RequiredArgsConstructor
@Entity
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
@AllArgsConstructor
@Table(name = "users")
public class User extends BaseEntity implements UserDetails, Principal {
    @Column(length = 100, nullable = false)
    String fullName;

    @Column(length = 100, nullable = false, unique = true)
    String email;

    @Column()
    String password;

    @Column()
    Boolean enabled;

    @Builder.Default
    @Column(nullable = false)
    String avatar = "https://sbcf.fr/wp-content/uploads/2018/03/sbcf-default-avatar.png";

    @Column(nullable = false)
            @ManyToMany(fetch = FetchType.EAGER)
    Set<Role> roles;

    @PrePersist
    public void prePersist() {
        this.password = new BCryptPasswordEncoder().encode(this.password);
    }
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return this.roles
                .stream()
                .map(role -> new SimpleGrantedAuthority(role.getName()))
                .toList();
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public String getName() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
