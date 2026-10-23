package com.seventeam.wallet.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void keepsExactTwoDecimalScale() {
        Money money = Money.of(new BigDecimal("20"), "PEN");
        assertThat(money.amount().toPlainString()).isEqualTo("20.00");
        assertThat(money.plus(Money.of(new BigDecimal("0.10"), "PEN")).amount()).isEqualByComparingTo("20.10");
    }

    @Test
    void rejectsMoreThanTwoDecimals() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("1.001"), "PEN")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMixedCurrencies() {
        Money pen = Money.of(BigDecimal.ONE, "PEN");
        Money usd = Money.of(BigDecimal.ONE, "USD");
        assertThatThrownBy(() -> pen.plus(usd)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addingTenCentsTenTimesIsExact() {
        Money total = Money.of(BigDecimal.ZERO, "PEN");
        for (int i = 0; i < 10; i++) {
            total = total.plus(Money.of(new BigDecimal("0.10"), "PEN"));
        }
        assertThat(total.amount()).isEqualByComparingTo("1.00");
    }
}
