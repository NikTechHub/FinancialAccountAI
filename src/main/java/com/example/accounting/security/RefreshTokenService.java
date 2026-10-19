package com.example.accounting.security;

import com.example.accounting.entity.RefreshToken;
import com.example.accounting.entity.User;
import com.example.accounting.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, JwtService jwtService) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
    }

    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Transactional
    public RefreshToken createRefreshToken(User user) {
        String rawToken = jwtService.generateRefreshToken(user.getId());
        String tokenHash = hashToken(rawToken);
        Instant expiresAt = Instant.now().plusMillis(jwtService.getRefreshExpiration());
        RefreshToken entity = new RefreshToken(user, tokenHash, expiresAt);
        refreshTokenRepository.save(entity);
        return entity;
    }

    @Transactional
    public String createAndReturnRawToken(User user) {
        String rawToken = jwtService.generateRefreshToken(user.getId());
        String tokenHash = hashToken(rawToken);
        Instant expiresAt = Instant.now().plusMillis(jwtService.getRefreshExpiration());
        RefreshToken entity = new RefreshToken(user, tokenHash, expiresAt);
        refreshTokenRepository.save(entity);
        return rawToken;
    }

    @Transactional(readOnly = true)
    public Optional<RefreshToken> findByRawToken(String rawToken) {
        String hash = hashToken(rawToken);
        return refreshTokenRepository.findByTokenHash(hash);
    }

    @Transactional
    public void revokeToken(String rawToken) {
        findByRawToken(rawToken).ifPresent(rt -> {
            rt.setRevoked(true);
            refreshTokenRepository.save(rt);
        });
    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }

    public boolean isValid(RefreshToken token) {
        return !token.getRevoked() && !token.isExpired() && !token.getExpiresAt().isBefore(Instant.now());
    }
}
