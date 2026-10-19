package com.example.accounting.dto;

import com.example.accounting.entity.AccountType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record AccountTreeResponse(
        UUID id,
        String code,
        String name,
        AccountType type,
        Boolean active,
        List<AccountTreeResponse> children
) {
    public AccountTreeResponse(UUID id, String code, String name, AccountType type, Boolean active) {
        this(id, code, name, type, active, new ArrayList<>());
    }
}
