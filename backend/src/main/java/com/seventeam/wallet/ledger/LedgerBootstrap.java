package com.seventeam.wallet.ledger;

import com.seventeam.wallet.shared.WalletProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Garantiza la cuenta de sistema usada como contrapartida del fondeo ficticio. */
@Component
@Order(0)
public class LedgerBootstrap implements ApplicationRunner {

    private final LedgerService ledger;
    private final WalletProperties props;

    public LedgerBootstrap(LedgerService ledger, WalletProperties props) {
        this.ledger = ledger;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        ledger.ensureSystemAccount(props.payment().currency());
    }
}
