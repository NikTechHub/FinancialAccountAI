package com.example.accounting.dto;

import jakarta.validation.constraints.Size;

public record ReviewActionRequest(
        @Size(max = 2000) String reviewComment
) {
}
