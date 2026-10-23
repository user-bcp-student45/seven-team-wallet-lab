package com.seventeam.wallet.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record User(
        UUID id,
        String email,
        String passwordHash,
        String fullName,
        String documentNumber,
        String phone,
        boolean phoneVerified,
        String phoneCodeHash,
        Instant phoneCodeExpires,
        int phoneCodeAttempts,
        UserStatus status,
        int completedSteps,
        int failedLogins,
        Instant lockedUntil) {

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public String maskedPhone() {
        return phone == null ? null : "+51 ••• ••• " + phone.substring(phone.length() - 3);
    }
}
