package com.example.accounting.service;

import com.example.accounting.audit.AuditAction;
import com.example.accounting.audit.AuditCommand;
import com.example.accounting.audit.AuditLogService;
import com.example.accounting.audit.AuditResourceType;
import com.example.accounting.audit.AuditSnapshots;
import com.example.accounting.dto.DocumentResponse;
import com.example.accounting.entity.*;
import com.example.accounting.exception.*;
import com.example.accounting.repository.*;
import com.example.accounting.security.OrganizationAccessService;
import com.example.accounting.storage.DocumentStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DocumentService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "jpg", "jpeg", "png", "webp");

    private final DocumentRepository documentRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationAccessService organizationAccessService;
    private final InvoiceRepository invoiceRepository;
    private final ExpenseRepository expenseRepository;
    private final UserRepository userRepository;
    private final DocumentStorageService storageService;
    private final AuditLogService auditLogService;

    private final long maxFileSize;

    public DocumentService(DocumentRepository documentRepository,
                           OrganizationRepository organizationRepository,
                           OrganizationMembershipRepository membershipRepository,
                           OrganizationAccessService organizationAccessService,
                           InvoiceRepository invoiceRepository,
                           ExpenseRepository expenseRepository,
                           UserRepository userRepository,
                           DocumentStorageService storageService,
                           @Value("${app.documents.max-file-size:10MB}") DataSize maxFileSize,
                           AuditLogService auditLogService) {
        this.documentRepository = documentRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.organizationAccessService = organizationAccessService;
        this.invoiceRepository = invoiceRepository;
        this.expenseRepository = expenseRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.maxFileSize = maxFileSize.toBytes();
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

    private void requireUploadPermission(UUID userId, UUID orgId) {
        var roleOpt = organizationAccessService.userRole(userId, orgId);
        var role = roleOpt.orElseThrow(() -> new AccessDeniedException("No membership"));
        if (role == MembershipRole.VIEWER || role == MembershipRole.EMPLOYEE) {
            throw new AccessDeniedException("Role " + role + " cannot upload documents");
        }
    }

    private void requireDeletePermission(UUID userId, UUID orgId) {
        var roleOpt = organizationAccessService.userRole(userId, orgId);
        var role = roleOpt.orElseThrow(() -> new AccessDeniedException("No membership"));
        if (role == MembershipRole.VIEWER || role == MembershipRole.EMPLOYEE) {
            throw new AccessDeniedException("Role " + role + " cannot delete documents");
        }
    }

    private String sanitizeExtension(String originalFilename, String contentType) {
        String ext = "";
        if (StringUtils.hasText(originalFilename) && originalFilename.contains(".")) {
            ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
        }
        // fallback from contentType
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            if ("application/pdf".equals(contentType)) ext = "pdf";
            else if ("image/jpeg".equals(contentType)) ext = "jpg";
            else if ("image/png".equals(contentType)) ext = "png";
            else if ("image/webp".equals(contentType)) ext = "webp";
        }
        return ext;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidDocumentException("File must not be empty");
        }
        if (file.getSize() > maxFileSize) {
            throw new DocumentSizeExceededException("File size " + file.getSize() + " exceeds maximum allowed " + maxFileSize);
        }
        String contentType = file.getContentType();
        if (contentType != null) {
            contentType = contentType.toLowerCase().split(";")[0].trim();
        }
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new UnsupportedDocumentTypeException("Unsupported content type: " + contentType + ". Allowed: " + ALLOWED_CONTENT_TYPES);
        }
        String ext = sanitizeExtension(file.getOriginalFilename(), contentType);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new UnsupportedDocumentTypeException("Unsupported file extension: " + ext);
        }
        // path traversal check on original filename
        String original = file.getOriginalFilename();
        if (original != null && (original.contains("..") || original.contains("/") || original.contains("\\"))) {
            // we will not use original for storage, but still validate
            if (original.contains("..")) {
                // allow but we sanitize - just ensure not treated as path
            }
        }
    }

    private String calculateChecksum(InputStream inputStream) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new DocumentStorageException("Failed to calculate checksum", e);
        }
    }

    @Transactional
    public DocumentResponse uploadDocument(UUID userId, MultipartFile file, DocumentType documentType,
                                           UUID invoiceId, UUID expenseId) {
        if (documentType == null) {
            throw new InvalidDocumentException("documentType must not be null");
        }
        // validate enum already by Spring, but double-check
        validateFile(file);

        if (invoiceId != null && expenseId != null) {
            throw new DocumentAssociationException("Document cannot be associated with both invoice and expense");
        }

        Organization org = resolveSingleOrganization(userId);
        requireUploadPermission(userId, org.getId());

        // Validate invoice/expense belong to same org
        Invoice invoice = null;
        if (invoiceId != null) {
            invoice = invoiceRepository.findById(invoiceId)
                    .orElseThrow(() -> new InvalidDocumentException("Invoice not found: " + invoiceId));
            if (!invoice.getOrganization().getId().equals(org.getId())) {
                throw new DocumentAssociationException("Invoice belongs to different organization");
            }
        }
        Expense expense = null;
        if (expenseId != null) {
            expense = expenseRepository.findById(expenseId)
                    .orElseThrow(() -> new InvalidDocumentException("Expense not found: " + expenseId));
            if (!expense.getOrganization().getId().equals(org.getId())) {
                throw new DocumentAssociationException("Expense belongs to different organization");
            }
        }

        // Generate IDs and storage key
        UUID documentId = UUID.randomUUID();
        String originalFileName = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "file";
        // sanitize original filename for display but not for storage
        String sanitizedOriginal = originalFileName.replaceAll("[^a-zA-Z0-9._-]", "_").replace("..", "__");
        // also remove any remaining path separators that might have been missed
        sanitizedOriginal = sanitizedOriginal.replace("/", "_").replace("\\", "_");
        if (sanitizedOriginal.length() > 255) sanitizedOriginal = sanitizedOriginal.substring(0, 255);

        String contentType = file.getContentType() != null ? file.getContentType().split(";")[0].trim().toLowerCase() : "application/octet-stream";
        String ext = sanitizeExtension(originalFileName, contentType);
        String storedFileName = documentId.toString() + "." + ext;
        String storageKey = "organizations/" + org.getId() + "/documents/" + documentId + "/" + storedFileName;

        // Calculate checksum and store
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to read file bytes", e);
        }
        String checksum;
        try (InputStream is = new java.io.ByteArrayInputStream(fileBytes)) {
            checksum = calculateChecksum(is);
        } catch (Exception e) {
            throw new DocumentStorageException("Checksum calculation failed", e);
        }

        // Store physical file
        try (InputStream is = new java.io.ByteArrayInputStream(fileBytes)) {
            storageService.store(is, storageKey, contentType, fileBytes.length);
        } catch (Exception e) {
            throw new DocumentStorageException("Failed to store document", e);
        }

        User user = userRepository.findById(userId).orElseThrow(() -> new RuntimeException("User not found"));

        Document doc = new Document(
                org, invoice, expense,
                sanitizedOriginal, storedFileName, storageKey,
                contentType, (long) fileBytes.length, documentType,
                checksum, user
        );
        // set id manually to match storage key documentId
        doc.setId(documentId);

        try {
            Document saved = documentRepository.save(doc);
            auditLogService.record(new AuditCommand(AuditAction.DOCUMENT_UPLOADED, AuditResourceType.DOCUMENT,
                    org.getId(), userId, saved.getId(), "Document uploaded",
                    null, auditLogService.toJson(AuditSnapshots.document(saved.getOriginalFileName(),
                            saved.getContentType(), saved.getFileSize(),
                            saved.getDocumentType() != null ? saved.getDocumentType().name() : null,
                            saved.getStatus() != null ? saved.getStatus().name() : null,
                            saved.getChecksum())),
                    auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
            return toResponse(saved);
        } catch (Exception e) {
            // cleanup stored file if DB fails
            try {
                storageService.delete(storageKey);
            } catch (Exception cleanupEx) {
                // log but don't mask original
            }
            throw new DocumentStorageException("Failed to persist document metadata", e);
        }
    }

    @Transactional(readOnly = true)
    public DocumentResponse getDocument(UUID userId, UUID documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!accessibleOrganizationIds(userId).contains(doc.getOrganization().getId())) {
            throw new DocumentNotFoundException(documentId);
        }
        return toResponse(doc);
    }

    @Transactional(readOnly = true)
    public Page<DocumentResponse> listDocuments(UUID userId, DocumentType documentType, DocumentStatus status,
                                                UUID invoiceId, UUID expenseId, String filename, Pageable pageable) {
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            return Page.empty(pageable);
        }
        Page<Document> page;
        if (invoiceId != null) {
            page = documentRepository.findByOrganizationIdInAndInvoiceId(orgIds, invoiceId, pageable);
        } else if (expenseId != null) {
            page = documentRepository.findByOrganizationIdInAndExpenseId(orgIds, expenseId, pageable);
        } else if (documentType != null) {
            page = documentRepository.findByOrganizationIdInAndDocumentType(orgIds, documentType, pageable);
        } else if (status != null) {
            page = documentRepository.findByOrganizationIdInAndStatus(orgIds, status, pageable);
        } else if (StringUtils.hasText(filename)) {
            page = documentRepository.findByOrganizationIdInAndOriginalFileNameContainingIgnoreCase(orgIds, filename.trim(), pageable);
        } else {
            page = documentRepository.findByOrganizationIdIn(orgIds, pageable);
        }
        return page.map(this::toResponse);
    }

    public DocumentDownloadData downloadDocument(UUID userId, UUID documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!accessibleOrganizationIds(userId).contains(doc.getOrganization().getId())) {
            throw new DocumentNotFoundException(documentId);
        }
        // any member can download (EMPLOYEE/VIEWER allowed)
        // existence check
        if (!storageService.exists(doc.getStorageKey())) {
            throw new DocumentStorageException("Physical file not found for document: " + documentId);
        }
        InputStream is = storageService.retrieve(doc.getStorageKey());
        // sanitize original filename for header
        String downloadName = doc.getOriginalFileName().replaceAll("[\"\\\\]", "_");
        return new DocumentDownloadData(is, doc.getContentType(), doc.getFileSize(), downloadName, doc.getStorageKey());
    }

    @Transactional
    public void deleteDocument(UUID userId, UUID documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!accessibleOrganizationIds(userId).contains(doc.getOrganization().getId())) {
            throw new DocumentNotFoundException(documentId);
        }
        requireDeletePermission(userId, doc.getOrganization().getId());

        String storageKey = doc.getStorageKey();
        // delete metadata first? but spec says delete physical file then metadata
        // We do file then metadata; if file missing, still delete metadata
        try {
            if (storageService.exists(storageKey)) {
                storageService.delete(storageKey);
            }
        } catch (Exception e) {
            // if file deletion fails, still try to delete metadata? For now, rethrow as 500
            throw new DocumentStorageException("Failed to delete physical file", e);
        }
        documentRepository.delete(doc);
        auditLogService.record(new AuditCommand(AuditAction.DOCUMENT_DELETED, AuditResourceType.DOCUMENT,
                doc.getOrganization().getId(), userId, documentId, "Document deleted",
                auditLogService.toJson(AuditSnapshots.document(doc.getOriginalFileName(),
                        doc.getContentType(), doc.getFileSize(),
                        doc.getDocumentType() != null ? doc.getDocumentType().name() : null,
                        doc.getStatus() != null ? doc.getStatus().name() : null,
                        doc.getChecksum())),
                null,
                auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
        // Do NOT delete invoice/expense
    }

    public record DocumentDownloadData(InputStream inputStream, String contentType, Long fileSize, String originalFileName, String storageKey) {}

    private DocumentResponse toResponse(Document d) {
        return new DocumentResponse(
                d.getId(),
                d.getOrganization().getId(),
                d.getInvoice() != null ? d.getInvoice().getId() : null,
                d.getExpense() != null ? d.getExpense().getId() : null,
                d.getOriginalFileName(),
                d.getContentType(),
                d.getFileSize(),
                d.getDocumentType(),
                d.getStatus(),
                d.getChecksum(),
                d.getUploadedBy().getId(),
                d.getCreatedAt(),
                d.getUpdatedAt()
        );
    }
}
