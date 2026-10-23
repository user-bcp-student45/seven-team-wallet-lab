package com.seventeam.wallet.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Importe decimal exacto con moneda explicita y escala fija de 2 decimales. */
public record Money(BigDecimal amount, String currency) {

    public static final int SCALE = 2;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.stripTrailingZeros().scale() > SCALE) {
            throw new IllegalArgumentException("El importe admite como maximo " + SCALE + " decimales");
        }
        amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Monedas distintas: " + currency + " y " + other.currency);
        }
    }
}
