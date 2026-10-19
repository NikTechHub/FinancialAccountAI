package com.example.accounting.service;

import com.example.accounting.audit.AuditAction;
import com.example.accounting.audit.AuditCommand;
import com.example.accounting.audit.AuditLogService;
import com.example.accounting.audit.AuditResourceType;
import com.example.accounting.audit.AuditSnapshots;
import com.example.accounting.dto.CreateUserRequest;
import com.example.accounting.dto.UserResponse;
import com.example.accounting.entity.User;
import com.example.accounting.exception.UserAlreadyExistsException;
import com.example.accounting.exception.UserNotFoundException;
import com.example.accounting.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        String normalizedEmail = request.email().toLowerCase().trim();
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new UserAlreadyExistsException(normalizedEmail);
        }

        String passwordHash = passwordEncoder.encode(request.password());

        User user = new User(
                request.firstName(),
                request.lastName(),
                normalizedEmail,
                passwordHash,
                true
        );

        try {
            User saved = userRepository.save(user);
            // Public self-registration: the created user is the actor and no
            // organization exists yet, so both stay unattributed-safe (org null).
            auditLogService.record(new AuditCommand(AuditAction.USER_CREATED, AuditResourceType.USER,
                    null, saved.getId(), saved.getId(), "User registered",
                    null, auditLogService.toJson(AuditSnapshots.user(saved.getFirstName(),
                            saved.getLastName(), saved.getEmail(), saved.getActive())),
                    auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new UserAlreadyExistsException(normalizedEmail);
        }
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        return toResponse(user);
    }

    private UserResponse toResponse(User user) {
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
}
