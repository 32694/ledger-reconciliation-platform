package io.github.user32694.ledgerplatform.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void createsPositiveCnyInCents() {
        assertThat(Money.cny(1200).cents()).isEqualTo(1200);
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        assertThatThrownBy(() -> Money.cny(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.cny(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
