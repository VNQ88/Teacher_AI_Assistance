package com.example.teacherassistantai.security;

import com.example.teacherassistantai.integration.redis.RedisTokenService;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtFilterTest {
    @Mock
    private JwtService jwtService;
    @Mock
    private UserDetailsService userDetailsService;
    @Mock
    private RedisTokenService redisTokenService;
    @Mock
    private FilterChain filterChain;

    private JwtFilter jwtFilter;

    @BeforeEach
    void setUp() {
        jwtFilter = new JwtFilter(jwtService, userDetailsService, redisTokenService);
    }

    @Test
    void doFilterInternal_shouldReturnUnauthorizedJsonWhenAccessTokenExpired() throws Exception {
        String accessToken = "expired-access-token";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/user/current");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(redisTokenService.isExists(accessToken)).thenReturn(false);
        when(jwtService.isTokenType(accessToken, TokenType.ACCESS))
                .thenThrow(new ExpiredJwtException(null, null, "expired"));

        jwtFilter.doFilter(request, response, filterChain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"error\":\"Unauthorized\""));
        assertTrue(response.getContentAsString().contains("\"message\":\"Access token has expired\""));
        verify(filterChain, never()).doFilter(any(ServletRequest.class), any(ServletResponse.class));
    }
}
