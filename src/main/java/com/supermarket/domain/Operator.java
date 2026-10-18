package com.supermarket.domain;

import java.util.Objects;

public record Operator(
        Long id,
        String username,
        String passwordHash,
        String name,
        String role,
        String desk,
        String phone,
        String maskedPhone,
        String initials
) {
    public Operator {
        Objects.requireNonNull(username, "Username cannot be null");
        Objects.requireNonNull(passwordHash, "Password hash cannot be null");
        Objects.requireNonNull(name, "Name cannot be null");
        Objects.requireNonNull(role, "Role cannot be null");
    }
}

