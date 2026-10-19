package com.example.accounting.service;

import com.example.accounting.audit.AuditAction;
import com.example.accounting.audit.AuditCommand;
import com.example.accounting.audit.AuditLogService;
import com.example.accounting.audit.AuditResourceType;
import com.example.accounting.audit.AuditSnapshots;
import com.example.accounting.dto.CreateMembershipRequest;
import com.example.accounting.dto.MembershipResponse;
import com.example.accounting.entity.Organization;
import com.example.accounting.entity.OrganizationMembership;
import com.example.accounting.entity.User;
import com.example.accounting.exception.MembershipAlreadyExistsException;
import com.example.accounting.exception.OrganizationNotFoundException;
import com.example.accounting.exception.UserNotFoundException;
import com.example.accounting.repository.OrganizationMembershipRepository;
import com.example.accounting.repository.OrganizationRepository;
import com.example.accounting.repository.UserRepository;
import com.example.accounting.security.AuthContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MembershipService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final AuditLogService auditLogService;

    public MembershipService(OrganizationRepository organizationRepository,
                           UserRepository userRepository,
                           OrganizationMembershipRepository membershipRepository,
                           AuditLogService auditLogService) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public MembershipResponse addMember(UUID organizationId, CreateMembershipRequest request) {
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new OrganizationNotFoundException(organizationId));

        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new UserNotFoundException(request.userId()));

        if (membershipRepository.existsByUserIdAndOrganizationId(request.userId(), organizationId)) {
            throw new MembershipAlreadyExistsException(request.userId(), organizationId);
        }

        OrganizationMembership membership = new OrganizationMembership(user, organization, request.role());

        try {
            OrganizationMembership saved = membershipRepository.save(membership);
            // The controller enforces OWNER/ADMIN before delegating; the actor
            // is the authenticated user from the server-side security context.
            UUID actorUserId = AuthContext.currentUserId();
            auditLogService.record(new AuditCommand(AuditAction.MEMBER_ADDED, AuditResourceType.MEMBERSHIP,
                    organizationId, actorUserId, saved.getId(), "Member added to organization",
                    null, auditLogService.toJson(AuditSnapshots.membership(user.getId(),
                            organizationId, saved.getRole().name())),
                    auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new MembershipAlreadyExistsException(request.userId(), organizationId);
        }
    }

    @Transactional(readOnly = true)
    public List<MembershipResponse> listMembers(UUID organizationId) {
        if (!organizationRepository.existsById(organizationId)) {
            throw new OrganizationNotFoundException(organizationId);
        }
        List<OrganizationMembership> memberships = membershipRepository.findByOrganizationId(organizationId);
        return memberships.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private MembershipResponse toResponse(OrganizationMembership membership) {
        return new MembershipResponse(
                membership.getId(),
                membership.getUser().getId(),
                membership.getUser().getEmail(),
                membership.getUser().getFirstName(),
                membership.getUser().getLastName(),
                membership.getOrganization().getId(),
                membership.getOrganization().getName(),
                membership.getRole(),
                membership.getCreatedAt()
        );
    }
}
