package com.seventeam.wallet.identity.application;

import com.seventeam.wallet.analytics.OutboxRepository;
import com.seventeam.wallet.identity.adapter.out.ContactRepository;
import com.seventeam.wallet.identity.adapter.out.OnboardingEventRepository;
import com.seventeam.wallet.identity.adapter.out.UserRepository;
import com.seventeam.wallet.identity.domain.User;
import com.seventeam.wallet.ledger.LedgerService;
import com.seventeam.wallet.shared.Hashing;
import com.seventeam.wallet.shared.Money;
import com.seventeam.wallet.shared.WalletException;
import com.seventeam.wallet.shared.WalletException.FieldIssue;
import com.seventeam.wallet.shared.WalletException.Kind;
import com.seventeam.wallet.shared.WalletProperties;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Onboarding guiado de 4 pasos: cuenta, datos personales, verificacion de celular y activacion. */
@Service
public class OnboardingService {

    public static final int TOTAL_STEPS = 4;
    public static final List<String> STEP_TITLES =
            List.of("Cuenta", "Datos personales", "Verificación de celular", "Activación");

    public record PhoneCodeIssued(Instant expiresAt, String demoCode) {}

    private final UserRepository users;
    private final ContactRepository contacts;
    private final OnboardingEventRepository events;
    private final OutboxRepository outbox;
    private final LedgerService ledger;
    private final PasswordEncoder encoder;
    private final WalletProperties props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public OnboardingService(UserRepository users, ContactRepository contacts, OnboardingEventRepository events,
                             OutboxRepository outbox, LedgerService ledger, PasswordEncoder encoder,
                             WalletProperties props, Clock clock) {
        this.users = users;
        this.contacts = contacts;
        this.events = events;
        this.outbox = outbox;
        this.ledger = ledger;
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
    }

    /** Paso 1: crea la cuenta con clave hasheada (Argon2id) y registra el inicio del onboarding. */
    @Transactional
    public User register(String email, String password, String correlationId) {
        requireStrongPassword(password);
        UUID id = UUID.randomUUID();
        try {
            users.insert(id, email.trim(), encoder.encode(password));
        } catch (DuplicateKeyException e) {
            throw new WalletException(Kind.CONFLICT, "REGISTRATION_REJECTED",
                    "No pudimos crear la cuenta con esos datos. Si ya tienes una, inicia sesión.");
        }
        events.record(id, "ONBOARDING_STARTED", null, correlationId);
        events.record(id, "STEP_COMPLETED", 1, correlationId);
        return users.findById(id).orElseThrow();
    }

    /** Paso 2. */
    @Transactional
    public User submitPersonalData(UUID userId, String fullName, String documentNumber, String correlationId) {
        User user = lockOnboardingUser(userId);
        try {
            users.updatePersonalData(userId, fullName.trim(), documentNumber);
        } catch (DuplicateKeyException e) {
            throw new WalletException(Kind.CONFLICT, "DOCUMENT_ALREADY_USED",
                    "No pudimos usar ese DNI. Verifica el número o contacta a soporte.",
                    List.of(new FieldIssue("documentNumber", "Verifica el DNI ingresado.")));
        }
        if (user.completedSteps() < 2) {
            events.record(userId, "STEP_COMPLETED", 2, correlationId);
        }
        return users.findById(userId).orElseThrow();
    }

    /** Paso 3a: sin proveedor SMS, el codigo solo se devuelve en modo demo. */
    @Transactional
    public PhoneCodeIssued requestPhoneCode(UUID userId, String phone) {
        User user = lockOnboardingUser(userId);
        requireStep(user, 2, "Completa primero tus datos personales.");
        String code = String.format("%06d", random.nextInt(1_000_000));
        Instant expires = clock.instant().plus(props.onboarding().phoneCodeTtl());
        users.storePhoneCode(userId, phone, Hashing.sha256Hex(userId + ":" + code), expires);
        return new PhoneCodeIssued(expires, props.onboarding().exposeDemoPhoneCode() ? code : null);
    }

    /** Paso 3b. */
    @Transactional(noRollbackFor = WalletException.class)
    public User verifyPhone(UUID userId, String code, String correlationId) {
        User user = lockOnboardingUser(userId);
        requireStep(user, 2, "Completa primero tus datos personales.");
        if (user.phoneVerified()) {
            return user;
        }
        if (user.phoneCodeHash() == null || user.phoneCodeExpires() == null
                || clock.instant().isAfter(user.phoneCodeExpires())) {
            throw new WalletException(Kind.UNPROCESSABLE, "PHONE_CODE_EXPIRED",
                    "El código venció. Solicita uno nuevo para continuar.");
        }
        if (user.phoneCodeAttempts() >= props.onboarding().phoneCodeMaxAttempts()) {
            throw new WalletException(Kind.UNPROCESSABLE, "PHONE_CODE_ATTEMPTS_EXCEEDED",
                    "Superaste los intentos permitidos. Solicita un código nuevo.");
        }
        if (!user.phoneCodeHash().equals(Hashing.sha256Hex(userId + ":" + code))) {
            users.incrementPhoneAttempts(userId);
            throw new WalletException(Kind.UNPROCESSABLE, "PHONE_CODE_INVALID",
                    "El código no es correcto. Revísalo e inténtalo otra vez.",
                    List.of(new FieldIssue("code", "Código incorrecto.")));
        }
        users.markPhoneVerified(userId);
        events.record(userId, "STEP_COMPLETED", 3, correlationId);
        return users.findById(userId).orElseThrow();
    }

    /** Paso 4: activa la wallet, crea su cuenta contable y emite eventos de cierre. */
    @Transactional
    public User activate(UUID userId, String correlationId) {
        User user = users.findByIdForUpdate(userId).orElseThrow(OnboardingService::unknownUser);
        if (user.isActive()) {
            return user;
        }
        if (user.completedSteps() < 3 || !user.phoneVerified()) {
            throw new WalletException(Kind.CONFLICT, "STEP_OUT_OF_ORDER",
                    "Verifica tu celular antes de activar la wallet.");
        }
        String currency = props.payment().currency();
        UUID accountId = ledger.createWalletAccount(userId, currency);
        seedInitialBalance(accountId, currency);
        contacts.addDemoContacts(userId);
        users.activate(userId, clock.instant());
        events.record(userId, "STEP_COMPLETED", 4, correlationId);
        events.record(userId, "ONBOARDING_COMPLETED", null, correlationId);
        outbox.append("USER", userId, "WALLET_ACTIVATED", "{\"userId\":\"" + userId + "\"}");
        return users.findById(userId).orElseThrow();
    }

    /** Fondeo ficticio inicial (Q-10): asiento SEED contra la cuenta de sistema. */
    public void seedInitialBalance(UUID accountId, String currency) {
        seedBalance(accountId, Money.of(props.onboarding().initialBalance(), currency));
    }

    public void seedBalance(UUID accountId, Money amount) {
        UUID txId = UUID.randomUUID();
        ledger.postTransfer(txId, "SEED", "SEED-" + txId, LedgerService.SYSTEM_ACCOUNT_ID, accountId, amount);
    }

    public List<StepView> steps(User user) {
        List<StepView> result = new ArrayList<>();
        int current = Math.min(user.completedSteps() + 1, TOTAL_STEPS);
        for (int i = 1; i <= TOTAL_STEPS; i++) {
            String state = user.isActive() || i <= user.completedSteps() ? "DONE" : i == current ? "CURRENT" : "PENDING";
            result.add(new StepView(i, STEP_TITLES.get(i - 1), state));
        }
        return result;
    }

    public record StepView(int number, String title, String state) {}

    private User lockOnboardingUser(UUID userId) {
        User user = users.findByIdForUpdate(userId).orElseThrow(OnboardingService::unknownUser);
        if (user.isActive()) {
            throw new WalletException(Kind.CONFLICT, "ONBOARDING_ALREADY_COMPLETED",
                    "Tu wallet ya está activa.");
        }
        return user;
    }

    private static void requireStep(User user, int minimumCompleted, String message) {
        if (user.completedSteps() < minimumCompleted) {
            throw new WalletException(Kind.CONFLICT, "STEP_OUT_OF_ORDER", message);
        }
    }

    private static WalletException unknownUser() {
        return new WalletException(Kind.UNAUTHENTICATED, "UNAUTHENTICATED", "Inicia sesión para continuar.");
    }

    private static void requireStrongPassword(String password) {
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        if (!letter || !digit) {
            throw new WalletException(Kind.VALIDATION, "VALIDATION_ERROR",
                    "Revisa los datos ingresados y corrígelos para continuar.",
                    List.of(new FieldIssue("password", "La clave debe incluir letras y números.")));
        }
    }
}
