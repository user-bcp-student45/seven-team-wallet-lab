package com.seventeam.wallet.shared;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Supuestos de MVP; los valores marcados como pendientes dependen de Q-04, Q-05, Q-07, Q-10 y Q-11. */
@ConfigurationProperties(prefix = "wallet")
public record WalletProperties(Payment payment, Onboarding onboarding, Security security, Demo demo) {

    public record Payment(String currency, BigDecimal maxAmount, Duration intentTtl) {}

    public record Onboarding(BigDecimal initialBalance, Duration phoneCodeTtl, int phoneCodeMaxAttempts,
                             boolean exposeDemoPhoneCode) {}

    public record Security(int maxFailedLogins, Duration lockout) {}

    public record Demo(boolean seedEnabled, String password) {}
}
