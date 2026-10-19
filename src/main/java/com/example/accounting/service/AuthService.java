package com.example.accounting.service;

import com.example.accounting.audit.AuditAction;
import com.example.accounting.audit.AuditCommand;
import com.example.accounting.audit.AuditLogService;
import com.example.accounting.audit.AuditResourceType;
import com.example.accounting.audit.AuditSnapshots;
import com.example.accounting.dto.*;
import com.example.accounting.entity.MembershipRole;
import com.example.accounting.entity.Organization;
import com.example.accounting.entity.OrganizationMembership;
import com.example.accounting.entity.RefreshToken;
import com.example.accounting.entity.User;
import com.example.accounting.exception.OrganizationAlreadyExistsException;
import com.example.accounting.exception.UserAlreadyExistsException;
import com.example.accounting.repository.OrganizationMembershipRepository;
import com.example.accounting.repository.OrganizationRepository;
import com.example.accounting.repository.RefreshTokenRepository;
import com.example.accounting.repository.UserRepository;
import com.example.accounting.security.JwtService;
import com.example.accounting.security.RefreshTokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuditLogService auditLogService;

    public AuthService(UserRepository userRepository,
                       OrganizationRepository organizationRepository,
                       OrganizationMembershipRepository membershipRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       RefreshTokenService refreshTokenService,
                       AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().toLowerCase().trim();
        if (userRepository.existsByEmail(email)) {
            throw new UserAlreadyExistsException(email);
        }
        if (organizationRepository.existsByName(request.organizationName())) {
            throw new OrganizationAlreadyExistsException(request.organizationName());
        }

        User user = new User(
                request.firstName(),
                request.lastName(),
                email,
                passwordEncoder.encode(request.password()),
                true
        );
        User savedUser = userRepository.save(user);

        Organization org = new Organization(
                request.organizationName(),
                request.legalName(),
                request.businessType(),
                request.country(),
                request.currency(),
                request.timezone()
        );
        Organization savedOrg = organizationRepository.save(org);

        OrganizationMembership membership = new OrganizationMembership(savedUser, savedOrg, MembershipRole.OWNER);
        membershipRepository.save(membership);

        // Registration creates a user, an organization and its OWNER membership.
        auditLogService.record(new AuditCommand(AuditAction.USER_CREATED, AuditResourceType.USER,
                savedOrg.getId(), savedUser.getId(), savedUser.getId(), "User registered",
                null, auditLogService.toJson(AuditSnapshots.user(savedUser.getFirstName(),
                        savedUser.getLastName(), savedUser.getEmail(), savedUser.getActive())),
                auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
        auditLogService.record(new AuditCommand(AuditAction.ORGANIZATION_CREATED, AuditResourceType.ORGANIZATION,
                savedOrg.getId(), savedUser.getId(), savedOrg.getId(), "Organization registered",
                null, auditLogService.toJson(AuditSnapshots.organization(savedOrg.getName(),
                        savedOrg.getBusinessType(), savedOrg.getCountry(), savedOrg.getCurrency(),
                        savedOrg.getTimezone())),
                auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
        auditLogService.record(new AuditCommand(AuditAction.MEMBER_ADDED, AuditResourceType.MEMBERSHIP,
                savedOrg.getId(), savedUser.getId(), membership.getId(), "Owner membership created",
                null, auditLogService.toJson(AuditSnapshots.membership(savedUser.getId(),
                        savedOrg.getId(), MembershipRole.OWNER.name())),
                auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));

        String accessToken = jwtService.generateAccessToken(savedUser.getId(), savedUser.getEmail());
        String refreshToken = refreshTokenService.createAndReturnRawToken(savedUser);

        return new AuthResponse(
                accessToken,
                refreshToken,
                "Bearer",
                jwtService.getAccessExpiration(),
                toUserResponse(savedUser),
                toOrgResponse(savedOrg)
        );
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = request.email().toLowerCase().trim();
        try {
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException("Invalid email or password"));

            if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
                auditLoginFailure(user, "Invalid password");
                throw new org.springframework.security.authentication.BadCredentialsException("Invalid email or password");
            }

            if (Boolean.FALSE.equals(user.getActive())) {
                auditLoginFailure(user, "User is disabled");
                throw new org.springframework.security.authentication.DisabledException("User is disabled");
            }

            String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
            String refreshToken = refreshTokenService.createAndReturnRawToken(user);

            // fetch primary org if any for response
            OrganizationResponse orgResponse = null;
            var memberships = membershipRepository.findByUserId(user.getId());
            UUID primaryOrgId = null;
            if (!memberships.isEmpty()) {
                Organization org = memberships.get(0).getOrganization();
                primaryOrgId = org.getId();
                orgResponse = toOrgResponse(org);
            }

            auditLogService.record(new AuditCommand(AuditAction.LOGIN_SUCCESS, AuditResourceType.USER,
                    primaryOrgId, user.getId(), user.getId(), "User logged in",
                    null, null,
                    auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));

            return new AuthResponse(
                    accessToken,
                    refreshToken,
                    "Bearer",
                    jwtService.getAccessExpiration(),
                    toUserResponse(user),
                    orgResponse
            );
        } catch (org.springframework.security.authentication.BadCredentialsException e) {
            if (userRepository.findByEmail(email).isEmpty()) {
                // Unknown email: unattributable security event (no org, no actor).
                auditLogService.recordInNewTransaction(new AuditCommand(AuditAction.LOGIN_FAILURE,
                        AuditResourceType.USER, null, null, null, "Failed login for unknown email",
                        null, null,
                        auditLogService.toJson(AuditSnapshots.metadata("API", null)), false));
            }
            throw e;
        }
    }

    /**
     * Records a failed login for a known user in a separate transaction so
     * the record survives the rollback of the failed login attempt.
     */
    private void auditLoginFailure(User user, String reason) {
        UUID primaryOrgId = membershipRepository.findByUserId(user.getId()).stream()
                .findFirst().map(m -> m.getOrganization().getId()).orElse(null);
        auditLogService.recordInNewTransaction(new AuditCommand(AuditAction.LOGIN_FAILURE,
                AuditResourceType.USER, primaryOrgId, user.getId(), user.getId(),
                "Failed login: " + reason, null, null,
                auditLogService.toJson(AuditSnapshots.metadata("API", null)), false));
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        String rawToken = request.refreshToken();
        if (!jwtService.isRefreshToken(rawToken) || !jwtService.isTokenValid(rawToken)) {
            throw new org.springframework.security.authentication.BadCredentialsException("Invalid refresh token");
        }

        String hash = refreshTokenService.hashToken(rawToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException("Invalid refresh token"));

        if (stored.getRevoked() || stored.isExpired()) {
            throw new org.springframework.security.authentication.BadCredentialsException("Refresh token expired or revoked");
        }

        User user = stored.getUser();
        if (Boolean.FALSE.equals(user.getActive())) {
            throw new org.springframework.security.authentication.DisabledException("User is disabled");
        }

        // Rotation: revoke old, issue new
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        String newAccessToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
        String newRefreshToken = refreshTokenService.createAndReturnRawToken(user);

        var memberships = membershipRepository.findByUserId(user.getId());
        OrganizationResponse orgResponse = null;
        if (!memberships.isEmpty()) {
            orgResponse = toOrgResponse(memberships.get(0).getOrganization());
        }

        return new AuthResponse(
                newAccessToken,
                newRefreshToken,
                "Bearer",
                jwtService.getAccessExpiration(),
                toUserResponse(user),
                orgResponse
        );
    }

    private UserResponse toUserResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getActive(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }

    private OrganizationResponse toOrgResponse(Organization org) {
        return new OrganizationResponse(
                org.getId(),
                org.getName(),
                org.getLegalName(),
                org.getBusinessType(),
                org.getCountry(),
                org.getCurrency(),
                org.getTimezone(),
                org.getCreatedAt(),
                org.getUpdatedAt()
        );
    }
}
