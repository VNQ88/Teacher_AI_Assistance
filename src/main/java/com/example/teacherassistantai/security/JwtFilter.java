package com.example.teacherassistantai.security;

import com.example.teacherassistantai.exception.ErrorResponse;
import com.example.teacherassistantai.integration.redis.RedisTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Service;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Date;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@Service
@RequiredArgsConstructor
@Slf4j
public class JwtFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final RedisTokenService redisTokenService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getServletPath().contains("auth")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        final String jwt;

        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        jwt = authorizationHeader.substring(7);
        if (redisTokenService.isExists(jwt)) {
            log.warn("JWT token is blacklisted");
            writeUnauthorizedResponse(response, request, "Access token has been revoked");
            return;
        }

        try {
            if (!jwtService.isTokenType(jwt, TokenType.ACCESS)) {
                log.warn("JWT token has invalid token type");
                writeUnauthorizedResponse(response, request, "Invalid access token");
                return;
            }

            Long userId = jwtService.extractUserId(jwt);
            if (redisTokenService.isTokenRevokedByUserInvalidation(userId, jwtService.extractIssuedAt(jwt))) {
                log.warn("JWT token is revoked by user invalidation, userId={}", userId);
                writeUnauthorizedResponse(response, request, "Access token has been revoked");
                return;
            }

            final String userEmail = jwtService.extractUsername(jwt);

            if (userEmail == null || SecurityContextHolder.getContext().getAuthentication() != null) {
                filterChain.doFilter(request, response);
                return;
            }

            UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);
            if (!jwtService.isTokenValid(jwt, userDetails)) {
                writeUnauthorizedResponse(response, request, "Invalid access token");
                return;
            }


            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
//        log.info("Setting authentication for user: {}", userDetails.getUsername());
            SecurityContextHolder.getContext().setAuthentication(authToken);
            filterChain.doFilter(request, response);
        } catch (ExpiredJwtException ex) {
            writeUnauthorizedResponse(response, request, "Access token has expired");
        } catch (UsernameNotFoundException ex) {
            writeUnauthorizedResponse(response, request, "Invalid access token");
        } catch (JwtException | IllegalArgumentException ex) {
            writeUnauthorizedResponse(response, request, "Invalid access token");
        }
    }

    private void writeUnauthorizedResponse(HttpServletResponse response,
                                           HttpServletRequest request,
                                           String message) throws IOException {
        response.setStatus(UNAUTHORIZED.value());
        response.setContentType(APPLICATION_JSON_VALUE);

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(new Date())
                .status(UNAUTHORIZED.value())
                .path(request.getRequestURI())
                .error(UNAUTHORIZED.getReasonPhrase())
                .message(message)
                .build();
        objectMapper.writeValue(response.getOutputStream(), errorResponse);
    }
}
