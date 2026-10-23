package com.seventeam.wallet.identity.adapter.in;

import com.seventeam.wallet.identity.adapter.out.ContactRepository;
import com.seventeam.wallet.identity.domain.User;
import com.seventeam.wallet.identity.application.AuthService;
import com.seventeam.wallet.shared.AuthenticatedUser;
import com.seventeam.wallet.shared.WalletException;
import com.seventeam.wallet.shared.WalletException.Kind;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contacts")
class ContactController {

    record ContactView(UUID id, String displayName, String phoneMasked) {}

    private final ContactRepository contacts;
    private final AuthService auth;

    ContactController(ContactRepository contacts, AuthService auth) {
        this.contacts = contacts;
        this.auth = auth;
    }

    @GetMapping
    List<ContactView> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        User user = auth.current(principal.userId());
        if (!user.isActive()) {
            throw new WalletException(Kind.CONFLICT, "WALLET_NOT_ACTIVE", "Activa tu wallet para ver tus contactos.");
        }
        return contacts.listActive(user.id()).stream()
                .map(c -> new ContactView(c.id(), c.fullName(), "+51 ••• ••• " + c.phone().substring(6)))
                .toList();
    }
}
