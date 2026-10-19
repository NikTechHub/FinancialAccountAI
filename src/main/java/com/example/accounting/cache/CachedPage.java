package com.example.accounting.cache;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Serializable page snapshot used for caching paginated DTO results.
 * Spring Data {@code PageImpl} does not round-trip reliably through Redis
 * JSON serialization, so services cache this DTO and convert back to a
 * {@code Page} at the API boundary.
 */
public record CachedPage<T>(
        List<T> content,
        long totalElements,
        int pageNumber,
        int pageSize,
        long totalPages,
        boolean first,
        boolean last
) {
    public CachedPage {
        content = content == null ? List.of() : List.copyOf(content);
    }

    public static <T> CachedPage<T> from(Page<T> page) {
        return new CachedPage<>(
                page.getContent(),
                page.getTotalElements(),
                page.getNumber(),
                page.getSize(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }

    public Page<T> toPage(Pageable pageable) {
        Pageable effective = pageable.isPaged()
                ? PageRequest.of(pageNumber, pageSize, pageable.getSort())
                : pageable;
        return new PageImpl<>(content, effective, totalElements);
    }
}
