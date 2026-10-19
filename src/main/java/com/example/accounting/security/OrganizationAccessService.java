package com.example.accounting.security;

import com.example.accounting.entity.MembershipRole;
import com.example.accounting.entity.OrganizationMembership;
import com.example.accounting.exception.OrganizationNotFoundException;
import com.example.accounting.repository.OrganizationMembershipRepository;
import com.example.accounting.repository.OrganizationRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class OrganizationAccessService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;

    public OrganizationAccessService(OrganizationRepository organizationRepository,
                                   OrganizationMembershipRepository membershipRepository) {
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
    }

    public void requireMembership(UUID userId, UUID organizationId) {
        if (userId == null) {
            throw new AccessDeniedException("Authentication required");
        }
        if (!organizationRepository.existsById(organizationId)) {
            throw new OrganizationNotFoundException(organizationId);
        }
        if (!membershipRepository.existsByUserIdAndOrganizationId(userId, organizationId)) {
            throw new AccessDeniedException("User does not have membership in organization " + organizationId);
        }
    }

    public void requireRole(UUID userId, UUID organizationId, MembershipRole... allowedRoles) {
        requireMembership(userId, organizationId);
        Optional<OrganizationMembership> membership = membershipRepository.findByUserIdAndOrganizationId(userId, organizationId);
        if (membership.isEmpty()) {
            throw new AccessDeniedException("Membership not found");
        }
        MembershipRole actual = membership.get().getRole();
        Set<MembershipRole> allowed = Set.of(allowedRoles);
        if (!allowed.contains(actual)) {
            throw new AccessDeniedException("Requires role " + allowed + " but was " + actual);
        }
    }

    public boolean hasMembership(UUID userId, UUID organizationId) {
        return membershipRepository.existsByUserIdAndOrganizationId(userId, organizationId);
    }

    public Optional<MembershipRole> userRole(UUID userId, UUID organizationId) {
        return membershipRepository.findByUserIdAndOrganizationId(userId, organizationId)
                .map(OrganizationMembership::getRole);
    }

    public java.util.List<UUID> getOrganizationIds(UUID userId) {
        return membershipRepository.findByUserId(userId).stream()
                .map(m -> m.getOrganization().getId())
                .collect(java.util.stream.Collectors.toList());
    }

    public java.util.List<UUID> hasMembershipForAny(UUID userId) {
        return getOrganizationIds(userId);
    }
}
