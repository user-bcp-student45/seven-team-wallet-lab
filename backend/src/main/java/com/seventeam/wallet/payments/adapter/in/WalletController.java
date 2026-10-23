package com.seventeam.wallet.payments.adapter.in;

import com.seventeam.wallet.payments.application.PaymentService;
import com.seventeam.wallet.shared.AuthenticatedUser;
import com.seventeam.wallet.shared.Money;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallet")
class WalletController {

    record WalletView(String balance, String currency) {}

    private final PaymentService payments;

    WalletController(PaymentService payments) {
        this.payments = payments;
    }

    @GetMapping
    WalletView wallet(@AuthenticationPrincipal AuthenticatedUser principal) {
        Money balance = payments.balanceOf(principal.userId());
        return new WalletView(balance.amount().toPlainString(), balance.currency());
    }
}
