package com.example.teacherassistantai.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import com.example.teacherassistantai.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@RequiredArgsConstructor
@Service
public class JwtService {
    private final UserService userService;

    @Value("${application.jwt.signer-key}")
    private String secretKey = "secretKey";


    public String generateAccessToken(UserDetails userDetails, Long duration) {
        return generateToken(new HashMap<>(), userDetails, duration, TokenType.ACCESS);
    }

    public String generateRefreshToken(UserDetails userDetails, Long duration) {
        return generateToken(new HashMap<>(), userDetails, duration, TokenType.REFRESH);
    }

    public String generateToken(UserDetails userDetails, Long duration) {
        return generateAccessToken(userDetails, duration);
    }

    public String generateToken(Map<String, Object> claims, UserDetails userDetails, Long duration, TokenType tokenType) {
        return buildToken(claims, userDetails, duration, tokenType);
    }

    private String buildToken(Map<String, Object> extractClaims, UserDetails userDetails, long jwtExpiration, TokenType tokenType) {
        var authorities = userDetails.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        var user = userDetails.getUsername();
        var userId = userService.getUserByEmail(user).getId();
        var now = new Date(System.currentTimeMillis());
        extractClaims.put("userId", userId);
        extractClaims.put("issuedAtMillis", now.getTime());
        extractClaims.put("tokenType", tokenType.name());
        return Jwts.builder().claims(extractClaims).subject(userDetails.getUsername())
                .claim("authorities", authorities)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + jwtExpiration))
                .signWith(getSignInKey())
                .compact();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public Date extractIssuedAt(String token) {
        Long issuedAtMillis = extractClaim(token, claims -> parseLongClaim(claims.get("issuedAtMillis")));
        if (issuedAtMillis != null) {
            return new Date(issuedAtMillis);
        }
        return extractClaim(token, Claims::getIssuedAt);
    }

    public Long extractUserId(String token) {
        return extractClaim(token, claims -> parseLongClaim(claims.get("userId")));
    }

    public boolean isTokenType(String token, TokenType tokenType) {
        String actualTokenType = extractClaim(token, claims -> {
            Object value = claims.get("tokenType");
            return value == null ? null : value.toString();
        });
        return tokenType.name().equals(actualTokenType);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    private Long parseLongClaim(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .setSigningKey(getSignInKey())
                .build()
                .parseSignedClaims(token).getPayload();
    }

    private Key getSignInKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
