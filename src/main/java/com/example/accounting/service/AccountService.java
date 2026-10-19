package com.example.accounting.service;

import com.example.accounting.audit.AuditAction;
import com.example.accounting.audit.AuditCommand;
import com.example.accounting.audit.AuditLogService;
import com.example.accounting.audit.AuditResourceType;
import com.example.accounting.audit.AuditSnapshots;
import com.example.accounting.dto.AccountResponse;
import com.example.accounting.dto.AccountTreeResponse;
import com.example.accounting.dto.CreateAccountRequest;
import com.example.accounting.dto.UpdateAccountRequest;
import com.example.accounting.entity.Account;
import com.example.accounting.entity.AccountType;
import com.example.accounting.entity.MembershipRole;
import com.example.accounting.entity.Organization;
import com.example.accounting.exception.AccountAlreadyExistsException;
import com.example.accounting.exception.AccountNotFoundException;
import com.example.accounting.exception.InvalidParentAccountException;
import com.example.accounting.exception.OrganizationNotFoundException;
import com.example.accounting.exception.SystemAccountModificationException;
import com.example.accounting.cache.CacheKeyBuilder;
import com.example.accounting.cache.CacheNames;
import com.example.accounting.cache.CachedPage;
import com.example.accounting.cache.TenantCacheService;
import com.example.accounting.repository.AccountRepository;
import com.example.accounting.repository.OrganizationMembershipRepository;
import com.example.accounting.repository.OrganizationRepository;
import com.example.accounting.security.OrganizationAccessService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationAccessService organizationAccessService;
    private final TenantCacheService tenantCache;
    private final AuditLogService auditLogService;

    public AccountService(AccountRepository accountRepository,
                          OrganizationRepository organizationRepository,
                          OrganizationMembershipRepository membershipRepository,
                          OrganizationAccessService organizationAccessService,
                          TenantCacheService tenantCache,
                          AuditLogService auditLogService) {
        this.accountRepository = accountRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.organizationAccessService = organizationAccessService;
        this.tenantCache = tenantCache;
        this.auditLogService = auditLogService;
    }

    private List<UUID> accessibleOrganizationIds(UUID userId) {
        return membershipRepository.findByUserId(userId).stream()
                .map(m -> m.getOrganization().getId())
                .collect(Collectors.toList());
    }

    private Organization resolveSingleOrganization(UUID userId) {
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            throw new OrganizationNotFoundException(UUID.randomUUID());
        }
        UUID primary = orgIds.get(0);
        return organizationRepository.findById(primary)
                .orElseThrow(() -> new OrganizationNotFoundException(primary));
    }

    private void requireCreatePermission(UUID userId, UUID orgId) {
        var roleOpt = organizationAccessService.userRole(userId, orgId);
        MembershipRole role = roleOpt.orElseThrow(() -> new AccessDeniedException("No membership"));
        if (role == MembershipRole.VIEWER || role == MembershipRole.EMPLOYEE) {
            throw new AccessDeniedException("Role " + role + " cannot modify chart of accounts");
        }
    }

    private void requireReadPermission(UUID userId, UUID orgId) {
        if (!membershipRepository.existsByUserIdAndOrganizationId(userId, orgId)) {
            throw new AccessDeniedException("No membership in organization");
        }
    }

    @Transactional
    public AccountResponse createAccount(UUID userId, CreateAccountRequest request) {
        Organization org = resolveSingleOrganization(userId);
        requireCreatePermission(userId, org.getId());

        if (accountRepository.existsByOrganizationIdAndCode(org.getId(), request.code())) {
            throw new AccountAlreadyExistsException(request.code());
        }

        Account parent = null;
        if (request.parentAccountId() != null) {
            parent = accountRepository.findById(request.parentAccountId())
                    .orElseThrow(() -> new InvalidParentAccountException("Parent account not found"));
            if (!parent.getOrganization().getId().equals(org.getId())) {
                throw new InvalidParentAccountException("Parent account belongs to different organization");
            }
            if (parent.getId().equals(request.parentAccountId()) && parent.getId() != null) {
                // self parent check will be done after creation; for now just check not same as new (new has no id yet)
            }
        }

        Account account = new Account(
                org,
                request.code().trim(),
                request.name().trim(),
                request.type(),
                parent,
                request.description() != null ? request.description().trim() : null,
                true,
                false
        );

        try {
            Account saved = accountRepository.save(account);
            evictAccountListAndTreeCaches(org.getId());
            auditLogService.record(new AuditCommand(AuditAction.ACCOUNT_CREATED, AuditResourceType.ACCOUNT,
                    org.getId(), userId, saved.getId(), "Account created",
                    null, auditLogService.toJson(AuditSnapshots.account(saved.getCode(), saved.getName(),
                            saved.getType() != null ? saved.getType().name() : null,
                            saved.getParentAccount() != null ? saved.getParentAccount().getId() : null,
                            saved.getDescription(), saved.getActive())),
                    auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new AccountAlreadyExistsException(request.code());
        }
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID userId, UUID accountId) {
        // Tenant-safe single-item cache: probe each accessible org's key
        // (keys are only built from validated membership org IDs).
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        for (UUID orgId : orgIds) {
            String key = CacheKeyBuilder.accountItem(orgId, accountId);
            var cached = tenantCache.get(CacheNames.ACCOUNTS_ITEM, key, AccountResponse.class);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (!orgIds.contains(account.getOrganization().getId())) {
            throw new AccountNotFoundException(accountId);
        }
        AccountResponse response = toResponse(account);
        tenantCache.put(CacheNames.ACCOUNTS_ITEM,
                CacheKeyBuilder.accountItem(account.getOrganization().getId(), accountId), response);
        return response;
    }

    @Transactional(readOnly = true)
    public Page<AccountResponse> listAccounts(UUID userId, String search, AccountType type,
                                             UUID parentAccountId, Pageable pageable) {
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            return Page.empty(pageable);
        }
        // Tenant-safe list cache: key includes org set + every result-shaping parameter.
        // Built only from validated membership org IDs, never from client org input.
        String listKey = CacheKeyBuilder.accountList(orgIds, search, type, parentAccountId, pageable);
        var cached = tenantCache.get(CacheNames.ACCOUNTS_LIST, listKey,
                new TypeReference<CachedPage<AccountResponse>>() {
                });
        if (cached.isPresent()) {
            return cached.get().toPage(pageable);
        }
        Page<AccountResponse> result = loadAccountPage(orgIds, search, type, parentAccountId, pageable);
        tenantCache.put(CacheNames.ACCOUNTS_LIST, listKey, CachedPage.from(result));
        return result;
    }

    private Page<AccountResponse> loadAccountPage(List<UUID> orgIds, String search, AccountType type,
                                                  UUID parentAccountId, Pageable pageable) {

        // If parentAccountId is supplied, verify it belongs to user's orgs
        if (parentAccountId != null) {
            Account parent = accountRepository.findById(parentAccountId)
                    .orElseThrow(() -> new InvalidParentAccountException("Parent account not found"));
            if (!orgIds.contains(parent.getOrganization().getId())) {
                throw new InvalidParentAccountException("Parent account belongs to different organization");
            }
            // filter by parent
            if (StringUtils.hasText(search) && type != null) {
                // use search + type + parent? For simplicity, use parent filter then in-memory? But we can use repository with parent + type + search
                // For now, handle parent + type + search combinations by fetching parent page and filtering
                // Use parent-specific query
                if (StringUtils.hasText(search) && type != null) {
                    // No direct repo for all three, fallback to search with parent in service by filtering
                    // We'll use a simple approach: fetch parent's children and filter
                    // But to keep pagination, we use parent query and then filter type/search in memory is not ideal
                    // Instead, we will use a custom handling: if all three present, use parent query and then filter
                    Page<Account> page = accountRepository.findByOrganizationIdInAndParentAccountIdAndActiveTrue(orgIds, parentAccountId, pageable);
                    // filter by type and search in memory for this edge case
                    List<AccountResponse> filtered = page.getContent().stream()
                            .filter(a -> type == null || a.getType() == type)
                            .filter(a -> !StringUtils.hasText(search) || a.getName().toLowerCase().contains(search.toLowerCase()) || a.getCode().toLowerCase().contains(search.toLowerCase()))
                            .map(this::toResponse)
                            .toList();
                    // This is not perfect pagination but acceptable for now
                    return new org.springframework.data.domain.PageImpl<>(filtered, pageable, filtered.size());
                } else if (type != null) {
                    Page<Account> page = accountRepository.findByOrganizationIdInAndParentAccountIdAndActiveTrue(orgIds, parentAccountId, pageable);
                    List<AccountResponse> filtered = page.getContent().stream()
                            .filter(a -> a.getType() == type)
                            .map(this::toResponse).toList();
                    return new org.springframework.data.domain.PageImpl<>(filtered, pageable, filtered.size());
                } else if (StringUtils.hasText(search)) {
                    Page<Account> page = accountRepository.findByOrganizationIdInAndParentAccountIdAndActiveTrue(orgIds, parentAccountId, pageable);
                    List<AccountResponse> filtered = page.getContent().stream()
                            .filter(a -> a.getName().toLowerCase().contains(search.toLowerCase()) || a.getCode().toLowerCase().contains(search.toLowerCase()))
                            .map(this::toResponse).toList();
                    return new org.springframework.data.domain.PageImpl<>(filtered, pageable, filtered.size());
                } else {
                    return accountRepository.findByOrganizationIdInAndParentAccountIdAndActiveTrue(orgIds, parentAccountId, pageable)
                            .map(this::toResponse);
                }
            }
            // else parent without other filters
            return accountRepository.findByOrganizationIdInAndParentAccountIdAndActiveTrue(orgIds, parentAccountId, pageable)
                    .map(this::toResponse);
        }

        if (StringUtils.hasText(search) && type != null) {
            return accountRepository.searchByNameOrCodeAndType(orgIds, type, search.trim(), pageable)
                    .map(this::toResponse);
        } else if (StringUtils.hasText(search)) {
            return accountRepository.searchByNameOrCode(orgIds, search.trim(), pageable)
                    .map(this::toResponse);
        } else if (type != null) {
            return accountRepository.findByOrganizationIdInAndTypeAndActiveTrue(orgIds, type, pageable)
                    .map(this::toResponse);
        } else {
            return accountRepository.findByOrganizationIdInAndActiveTrue(orgIds, pageable)
                    .map(this::toResponse);
        }
    }

    @Transactional
    public AccountResponse updateAccount(UUID userId, UUID accountId, UpdateAccountRequest request) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (!accessibleOrganizationIds(userId).contains(account.getOrganization().getId())) {
            throw new AccountNotFoundException(accountId);
        }

        // RBAC check
        var role = organizationAccessService.userRole(userId, account.getOrganization().getId()).orElse(null);
        if (role == MembershipRole.VIEWER || role == MembershipRole.EMPLOYEE) {
            throw new AccessDeniedException("Role " + role + " cannot modify chart of accounts");
        }

        var before = AuditSnapshots.account(account.getCode(), account.getName(),
                account.getType() != null ? account.getType().name() : null,
                account.getParentAccount() != null ? account.getParentAccount().getId() : null,
                account.getDescription(), account.getActive());

        if (request.code() != null && StringUtils.hasText(request.code()) && !request.code().trim().equals(account.getCode())) {
            if (accountRepository.existsByOrganizationIdAndCode(account.getOrganization().getId(), request.code().trim())) {
                throw new AccountAlreadyExistsException(request.code().trim());
            }
            account.setCode(request.code().trim());
        }
        if (request.name() != null && StringUtils.hasText(request.name())) {
            account.setName(request.name().trim());
        }
        if (request.type() != null) {
            account.setType(request.type());
        }
        if (request.description() != null) {
            account.setDescription(request.description().trim().isEmpty() ? null : request.description().trim());
        }
        if (request.active() != null) {
            if (Boolean.FALSE.equals(request.active()) && Boolean.TRUE.equals(account.getSystemAccount())) {
                throw new SystemAccountModificationException("Cannot deactivate system account");
            }
            account.setActive(request.active());
        }
        if (request.parentAccountId() != null) {
            if (request.parentAccountId().equals(accountId)) {
                throw new InvalidParentAccountException("Account cannot be its own parent");
            }
            Account parent = accountRepository.findById(request.parentAccountId())
                    .orElseThrow(() -> new InvalidParentAccountException("Parent account not found"));
            if (!parent.getOrganization().getId().equals(account.getOrganization().getId())) {
                throw new InvalidParentAccountException("Parent account belongs to different organization");
            }
            // prevent circular? Check not parent is descendant of account (simple: not same)
            account.setParentAccount(parent);
        } else if (request.parentAccountId() == null && request.code() != null) {
            // If explicitly null, we could clear parent if request contains null and we want to allow clearing
            // But UpdateAccountRequest parentAccountId can be null to mean no change or clear? We treat null as no change.
            // To clear, client should send explicit null and we handle if they intend to clear.
            // For now, null means no change.
        }

        try {
            Account saved = accountRepository.save(account);
            UUID orgId = saved.getOrganization().getId();
            tenantCache.evict(CacheNames.ACCOUNTS_ITEM, CacheKeyBuilder.accountItem(orgId, accountId));
            evictAccountListAndTreeCaches(orgId);
            auditLogService.record(new AuditCommand(AuditAction.ACCOUNT_UPDATED, AuditResourceType.ACCOUNT,
                    orgId, userId, saved.getId(), "Account updated",
                    auditLogService.toJson(before),
                    auditLogService.toJson(AuditSnapshots.account(saved.getCode(), saved.getName(),
                            saved.getType() != null ? saved.getType().name() : null,
                            saved.getParentAccount() != null ? saved.getParentAccount().getId() : null,
                            saved.getDescription(), saved.getActive())),
                    auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new AccountAlreadyExistsException(request.code() != null ? request.code() : account.getCode());
        }
    }

    @Transactional
    public void deactivateAccount(UUID userId, UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (!accessibleOrganizationIds(userId).contains(account.getOrganization().getId())) {
            throw new AccountNotFoundException(accountId);
        }
        var role = organizationAccessService.userRole(userId, account.getOrganization().getId()).orElse(null);
        if (role == MembershipRole.VIEWER || role == MembershipRole.EMPLOYEE) {
            throw new AccessDeniedException("Role " + role + " cannot deactivate accounts");
        }
        if (Boolean.TRUE.equals(account.getSystemAccount())) {
            throw new SystemAccountModificationException("Cannot deactivate system account");
        }
        var before = AuditSnapshots.account(account.getCode(), account.getName(),
                account.getType() != null ? account.getType().name() : null,
                account.getParentAccount() != null ? account.getParentAccount().getId() : null,
                account.getDescription(), account.getActive());
        account.setActive(false);
        accountRepository.save(account);
        UUID orgId = account.getOrganization().getId();
        tenantCache.evict(CacheNames.ACCOUNTS_ITEM, CacheKeyBuilder.accountItem(orgId, accountId));
        evictAccountListAndTreeCaches(orgId);
        auditLogService.record(new AuditCommand(AuditAction.ACCOUNT_DEACTIVATED, AuditResourceType.ACCOUNT,
                orgId, userId, account.getId(), "Account deactivated",
                auditLogService.toJson(before),
                auditLogService.toJson(AuditSnapshots.account(account.getCode(), account.getName(),
                        account.getType() != null ? account.getType().name() : null,
                        account.getParentAccount() != null ? account.getParentAccount().getId() : null,
                        account.getDescription(), false)),
                auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
    }

    @Transactional(readOnly = true)
    public List<AccountTreeResponse> getTree(UUID userId) {
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            return List.of();
        }
        // Tenant-safe tree cache: key contains the validated org set.
        String treeKey = CacheKeyBuilder.accountTree(orgIds);
        var cached = tenantCache.get(CacheNames.ACCOUNTS_TREE, treeKey,
                new TypeReference<List<AccountTreeResponse>>() {
                });
        if (cached.isPresent()) {
            return cached.get();
        }
        List<Account> allActive = accountRepository.findAllActiveOrdered(orgIds);
        // Build tree: map id -> node, then link children
        Map<UUID, AccountTreeResponse> map = new LinkedHashMap<>();
        List<AccountTreeResponse> roots = new ArrayList<>();

        for (Account acc : allActive) {
            AccountTreeResponse node = new AccountTreeResponse(
                    acc.getId(), acc.getCode(), acc.getName(), acc.getType(), acc.getActive()
            );
            map.put(acc.getId(), node);
        }

        for (Account acc : allActive) {
            AccountTreeResponse node = map.get(acc.getId());
            if (acc.getParentAccount() != null && map.containsKey(acc.getParentAccount().getId())) {
                AccountTreeResponse parentNode = map.get(acc.getParentAccount().getId());
                parentNode.children().add(node);
            } else {
                roots.add(node);
            }
        }

        // sort roots and children by code
        roots.sort(Comparator.comparing(AccountTreeResponse::code));
        for (AccountTreeResponse root : roots) {
            sortChildrenRecursively(root);
        }

        tenantCache.put(CacheNames.ACCOUNTS_TREE, treeKey, roots);
        return roots;
    }

    /**
     * Invalidate the organization's account tree and all list/search caches.
     * Scoped to the single organization namespace; other organizations are untouched.
     */
    private void evictAccountListAndTreeCaches(UUID organizationId) {
        tenantCache.evictOrgCategory(organizationId, CacheNames.ACCOUNTS_TREE);
        tenantCache.evictOrgCategory(organizationId, CacheNames.ACCOUNTS_LIST);
    }

    private void sortChildrenRecursively(AccountTreeResponse node) {
        node.children().sort(Comparator.comparing(AccountTreeResponse::code));
        for (AccountTreeResponse child : node.children()) {
            sortChildrenRecursively(child);
        }
    }

    private AccountResponse toResponse(Account a) {
        return new AccountResponse(
                a.getId(),
                a.getOrganization().getId(),
                a.getOrganization().getName(),
                a.getCode(),
                a.getName(),
                a.getType(),
                a.getParentAccount() != null ? a.getParentAccount().getId() : null,
                a.getParentAccount() != null ? a.getParentAccount().getName() : null,
                a.getDescription(),
                a.getActive(),
                a.getSystemAccount(),
                a.getCreatedAt(),
                a.getUpdatedAt()
        );
    }
}
