package com.example.accounting.cache;

import com.example.accounting.dto.CreateAccountRequest;
import com.example.accounting.entity.Account;
import com.example.accounting.entity.AccountType;
import com.example.accounting.entity.MembershipRole;
import com.example.accounting.entity.Organization;
import com.example.accounting.entity.OrganizationMembership;
import com.example.accounting.entity.User;
import com.example.accounting.repository.AccountRepository;
import com.example.accounting.repository.OrganizationMembershipRepository;
import com.example.accounting.repository.OrganizationRepository;
import com.example.accounting.security.OrganizationAccessService;
import com.example.accounting.service.AccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hit/miss verification for chart-of-accounts caching using mocked
 * repositories and a real (in-memory) TenantCacheService. No Redis server needed.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceCacheTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private OrganizationMembershipRepository membershipRepository;
    @Mock
    private OrganizationAccessService organizationAccessService;
    @Mock
    private com.example.accounting.audit.AuditLogService auditLogService;

    private TenantCacheService tenantCache;
    private AccountService accountService;

    private final UUID userA = UUID.randomUUID();
    private final UUID orgAId = UUID.randomUUID();
    private Organization orgA;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager(CacheNames.all());
        cacheManager.setAllowNullValues(false);
        RedisProperties props = new RedisProperties(); // enabled=false
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ObjectProvider<org.springframework.data.redis.core.RedisTemplate<String, Object>> provider =
                mock(ObjectProvider.class);
        Mockito.lenient().when(provider.getIfAvailable()).thenReturn(null);
        tenantCache = new TenantCacheService(cacheManager, props, objectMapper, provider);
        accountService = new AccountService(accountRepository, organizationRepository,
                membershipRepository, organizationAccessService, tenantCache, auditLogService);

        orgA = new Organization("Org A", null, null, null, null, null);
        orgA.setId(orgAId);
        User user = new User("First", "Last", "a@example.com", "hash", true);
        OrganizationMembership membership = new OrganizationMembership(user, orgA, MembershipRole.OWNER);
        when(membershipRepository.findByUserId(userA)).thenReturn(List.of(membership));
        Mockito.lenient().when(organizationAccessService.userRole(userA, orgAId))
                .thenReturn(Optional.of(MembershipRole.OWNER));
    }

    private Account account(String code, String name) {
        Account acc = new Account(orgA, code, name, AccountType.ASSET, null, null, true, false);
        acc.setId(UUID.randomUUID());
        return acc;
    }

    @Test
    void treeSecondCallUsesCache() {
        when(accountRepository.findAllActiveOrdered(anyList()))
                .thenReturn(List.of(account("1000", "Cash"), account("1100", "Bank")));

        var first = accountService.getTree(userA);
        var second = accountService.getTree(userA);

        assertThat(first).hasSize(2);
        assertThat(second).isEqualTo(first);
        verify(accountRepository, times(1)).findAllActiveOrdered(anyList());
        assertThat(tenantCache.getHits()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void listSecondCallUsesCache() {
        Pageable pageable = PageRequest.of(0, 20);
        var page = new org.springframework.data.domain.PageImpl<>(List.of(account("1000", "Cash")));
        when(accountRepository.findByOrganizationIdInAndActiveTrue(anyList(), any(Pageable.class)))
                .thenReturn(page);

        var first = accountService.listAccounts(userA, null, null, null, pageable);
        var second = accountService.listAccounts(userA, null, null, null, pageable);

        assertThat(first.getTotalElements()).isEqualTo(1);
        assertThat(second.getContent()).isEqualTo(first.getContent());
        verify(accountRepository, times(1)).findByOrganizationIdInAndActiveTrue(anyList(), any(Pageable.class));
    }

    @Test
    void createInvalidatesListAndTreeCaches() {
        Pageable pageable = PageRequest.of(0, 20);
        when(accountRepository.findAllActiveOrdered(anyList()))
                .thenReturn(List.of(account("1000", "Cash")));
        when(accountRepository.findByOrganizationIdInAndActiveTrue(anyList(), any(Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(account("1000", "Cash"))));

        // Populate caches.
        accountService.getTree(userA);
        accountService.listAccounts(userA, null, null, null, pageable);
        verify(accountRepository, times(1)).findAllActiveOrdered(anyList());

        // Mutate.
        when(organizationRepository.findById(orgAId)).thenReturn(Optional.of(orgA));
        when(accountRepository.existsByOrganizationIdAndCode(orgAId, "2000")).thenReturn(false);
        Account saved = account(orgA, "2000", "New Account");
        when(accountRepository.save(any(Account.class))).thenReturn(saved);
        accountService.createAccount(userA, new CreateAccountRequest("2000", "New Account", AccountType.ASSET, null, null));

        // Caches were invalidated: next reads hit the repository again.
        when(accountRepository.findAllActiveOrdered(anyList()))
                .thenReturn(List.of(account("1000", "Cash"), account("2000", "New Account")));
        var tree = accountService.getTree(userA);
        assertThat(tree).hasSize(2);
        verify(accountRepository, times(2)).findAllActiveOrdered(anyList());
    }

    @Test
    void differentOrgsDoNotShareTreeCache() {
        UUID userB = UUID.randomUUID();
        UUID orgBId = UUID.randomUUID();
        Organization orgB = new Organization("Org B", null, null, null, null, null);
        orgB.setId(orgBId);
        User userEntityB = new User("B", "User", "b@example.com", "hash", true);
        when(membershipRepository.findByUserId(userB))
                .thenReturn(List.of(new OrganizationMembership(userEntityB, orgB, MembershipRole.OWNER)));

        Account accA = account("1000", "Cash A");
        Account accB = new Account(orgB, "1000", "Cash B", AccountType.ASSET, null, null, true, false);
        accB.setId(UUID.randomUUID());
        when(accountRepository.findAllActiveOrdered(anyList()))
                .thenAnswer(inv -> {
                    List<UUID> ids = inv.getArgument(0);
                    if (ids.contains(orgBId)) {
                        return List.of(accB);
                    }
                    return List.of(accA);
                });

        var treeA = accountService.getTree(userA);
        var treeB = accountService.getTree(userB);

        assertThat(treeA.get(0).name()).isEqualTo("Cash A");
        assertThat(treeB.get(0).name()).isEqualTo("Cash B");
        // One repository call per org: B's request was not satisfied by A's cache.
        verify(accountRepository, times(2)).findAllActiveOrdered(anyList());
    }

    private Account account(Organization org, String code, String name) {
        Account acc = new Account(org, code, name, AccountType.ASSET, null, null, true, false);
        acc.setId(UUID.randomUUID());
        return acc;
    }
}
