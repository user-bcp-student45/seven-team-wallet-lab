package com.seventeam.wallet.demo;

import com.seventeam.wallet.identity.adapter.out.ContactRepository;
import com.seventeam.wallet.identity.adapter.out.UserRepository;
import com.seventeam.wallet.identity.application.OnboardingService;
import com.seventeam.wallet.ledger.LedgerService;
import com.seventeam.wallet.shared.Money;
import com.seventeam.wallet.shared.WalletProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Datos sinteticos de demo (contactos y un pagador). Solo con WALLET_DEMO_SEED=true y clave por entorno. */
@Component
@Order(10)
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private record DemoUser(String email, String fullName, String dni, String phone, boolean contact) {}

    private static final List<DemoUser> USERS = List.of(
            new DemoUser("carlos.quispe@demo.kipu.example", "Carlos Quispe", "70000001", "912345987", true),
            new DemoUser("ana.torres@demo.kipu.example", "Ana Torres", "70000002", "912345111", true),
            new DemoUser("luis.mendoza@demo.kipu.example", "Luis Mendoza", "70000003", "912345222", true),
            new DemoUser("maria.fernandez@demo.kipu.example", "María Fernández", "70000004", "912345333", false));

    private final WalletProperties props;
    private final UserRepository users;
    private final ContactRepository contacts;
    private final LedgerService ledger;
    private final OnboardingService onboarding;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;

    public DemoDataInitializer(WalletProperties props, UserRepository users, ContactRepository contacts,
                               LedgerService ledger, OnboardingService onboarding, PasswordEncoder encoder,
                               TransactionTemplate tx) {
        this.props = props;
        this.users = users;
        this.contacts = contacts;
        this.ledger = ledger;
        this.onboarding = onboarding;
        this.encoder = encoder;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.demo().seedEnabled()) {
            return;
        }
        String password = props.demo().password();
        if (password == null || password.length() < 10) {
            log.warn("WALLET_DEMO_SEED activo pero WALLET_DEMO_PASSWORD no cumple el minimo; se omite el sembrado");
            return;
        }
        for (DemoUser demo : USERS) {
            if (users.findByEmail(demo.email()).isPresent()) {
                continue;
            }
            tx.executeWithoutResult(status -> seed(demo, password));
        }
        // Los usuarios sembrados que no son contacto reciben a los contactos demo.
        for (DemoUser demo : USERS) {
            users.findByEmail(demo.email()).ifPresent(u -> tx.executeWithoutResult(s -> contacts.addDemoContacts(u.id())));
        }
        log.info("Datos demo sembrados ({} usuarios sinteticos)", USERS.size());
    }

    private void seed(DemoUser demo, String password) {
        UUID id = UUID.randomUUID();
        users.insertActiveDemo(id, demo.email(), encoder.encode(password), demo.fullName(), demo.dni(),
                demo.phone(), demo.contact());
        String currency = props.payment().currency();
        UUID account = ledger.createWalletAccount(id, currency);
        onboarding.seedBalance(account, Money.of(new BigDecimal("200.00"), currency));
    }
}
