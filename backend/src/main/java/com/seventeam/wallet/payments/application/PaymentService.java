package com.seventeam.wallet.payments.application;

import com.seventeam.wallet.analytics.OutboxRepository;
import com.seventeam.wallet.identity.adapter.out.ContactRepository;
import com.seventeam.wallet.identity.adapter.out.UserRepository;
import com.seventeam.wallet.identity.domain.User;
import com.seventeam.wallet.ledger.LedgerService;
import com.seventeam.wallet.payments.adapter.out.PaymentRepository;
import com.seventeam.wallet.payments.adapter.out.PaymentRepository.AttemptRow;
import com.seventeam.wallet.payments.adapter.out.PaymentRepository.IntentRow;
import com.seventeam.wallet.payments.adapter.out.PaymentRepository.MovementRow;
import com.seventeam.wallet.payments.adapter.out.PaymentRepository.PaymentRow;
import com.seventeam.wallet.shared.Hashing;
import com.seventeam.wallet.shared.Money;
import com.seventeam.wallet.shared.RequestContext;
import com.seventeam.wallet.shared.WalletException;
import com.seventeam.wallet.shared.WalletException.FieldIssue;
import com.seventeam.wallet.shared.WalletException.Kind;
import com.seventeam.wallet.shared.WalletProperties;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pago interno entre contactos: una unica transaccion ACID (debito, credito, resultado, clave de idempotencia
 * y outbox). Un reintento con la misma clave devuelve el resultado original sin mover fondos.
 */
@Service
public class PaymentService {

    public record Destination(UUID contactId, String displayName, String phoneMasked, boolean verified) {}

    public record IntentView(UUID intentId, Destination destination, String amount, String currency, String fee,
                             Instant expiresAt, String idempotencyKey) {}

    public record PaymentView(UUID paymentId, String reference, String status, String failureCode, String message,
                              String amount, String currency, String destinationName, Instant createdAt) {}

    public record ConfirmResult(PaymentView payment, boolean replayed) {}

    public record MovementView(UUID paymentId, String reference, String direction, String counterpartName,
                               String amount, String currency, String status, Instant createdAt) {}

    public record AttemptView(UUID attemptId, UUID paymentId, String reference, String kind, String outcome,
                              String requestId, String correlationId, Instant createdAt) {}

    private final PaymentRepository payments;
    private final UserRepository users;
    private final ContactRepository contacts;
    private final LedgerService ledger;
    private final OutboxRepository outbox;
    private final WalletProperties props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PaymentService(PaymentRepository payments, UserRepository users, ContactRepository contacts,
                          LedgerService ledger, OutboxRepository outbox, WalletProperties props, Clock clock) {
        this.payments = payments;
        this.users = users;
        this.contacts = contacts;
        this.ledger = ledger;
        this.outbox = outbox;
        this.props = props;
        this.clock = clock;
    }

    /** Pantalla de revision: destino verificado, monto y clave de idempotencia del intento. */
    @Transactional
    public IntentView createIntent(UUID payerId, UUID contactId, BigDecimal rawAmount) {
        User payer = requireActive(payerId);
        Money amount = validateAmount(rawAmount);
        User destination = users.findById(contactId)
                .filter(User::isActive)
                .filter(u -> contacts.exists(payerId, u.id()))
                .orElseThrow(() -> new WalletException(Kind.UNPROCESSABLE, "DESTINATION_NOT_CONTACT",
                        "Elige un contacto de tu lista para pagar."));

        UUID intentId = UUID.randomUUID();
        Instant now = clock.instant();
        String key = Hashing.sha256Hex(
                payer.id() + "|" + amount.amount().toPlainString() + "|" + destination.id() + "|" + intentId);
        IntentRow intent = new IntentRow(intentId, payer.id(), destination.id(), amount.amount(),
                amount.currency(), key, now.plus(props.payment().intentTtl()));
        payments.insertIntent(intent, now);
        return new IntentView(intentId,
                new Destination(destination.id(), destination.fullName(), destination.maskedPhone(), true),
                amount.amount().toPlainString(), amount.currency(), "0.00", intent.expiresAt(), key);
    }

    @Transactional
    public ConfirmResult confirm(UUID payerId, UUID intentId, String idempotencyKey, RequestContext ctx) {
        User payer = requireActive(payerId);
        IntentRow intent = payments.findIntent(intentId)
                .filter(i -> i.payerId().equals(payerId))
                .orElseThrow(() -> new WalletException(Kind.NOT_FOUND, "INTENT_NOT_FOUND",
                        "No encontramos ese intento de pago."));
        if (!intent.idempotencyKey().equals(idempotencyKey)) {
            throw new WalletException(Kind.UNPROCESSABLE, "IDEMPOTENCY_KEY_MISMATCH",
                    "La clave de idempotencia no corresponde a este intento de pago.");
        }
        String fingerprint = Hashing.sha256Hex(
                payerId + "|" + intent.amount().toPlainString() + "|" + intent.destinationId());

        UUID payerAccount = ledger.walletAccountOf(payerId).orElseThrow(
                () -> new WalletException(Kind.CONFLICT, "WALLET_NOT_ACTIVE", "Activa tu wallet para pagar."));
        // Serializa los intentos del pagador: evita doble gasto y carreras de la misma clave.
        ledger.lock(payerAccount);

        Optional<PaymentRow> existing = payments.findByKey(payerId, idempotencyKey);
        if (existing.isPresent()) {
            PaymentRow original = existing.get();
            if (!original.fingerprint().equals(fingerprint)) {
                throw new WalletException(Kind.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT",
                        "Esta clave ya se usó con otros datos de pago.");
            }
            payments.insertAttempt(original.id(), payerId, idempotencyKey, "DUPLICATE", original.status(),
                    ctx.requestId(), ctx.correlationId());
            return new ConfirmResult(toPayment(original), true);
        }

        Instant now = clock.instant();
        Money amount = Money.of(intent.amount(), intent.currency());
        String status;
        String failureCode = null;
        User payee = users.findById(intent.destinationId()).filter(User::isActive).orElse(null);
        if (now.isAfter(intent.expiresAt())) {
            status = "EXPIRED";
            failureCode = "INTENT_EXPIRED";
        } else if (payee == null || !contacts.exists(payerId, payee.id())) {
            status = "FAILED";
            failureCode = "DESTINATION_UNAVAILABLE";
        } else if (ledger.balance(payerAccount, amount.currency()).amount().compareTo(amount.amount()) < 0) {
            status = "FAILED";
            failureCode = "INSUFFICIENT_FUNDS";
        } else {
            status = "SUCCESS";
        }

        UUID paymentId = UUID.randomUUID();
        String reference = newReference();
        payments.insertPayment(paymentId, reference, intent.id(), payer.id(), intent.destinationId(),
                amount.amount(), amount.currency(), status, failureCode, idempotencyKey, fingerprint, now);

        if ("SUCCESS".equals(status)) {
            UUID payeeAccount = ledger.walletAccountOf(intent.destinationId()).orElseThrow(
                    () -> new IllegalStateException("El destinatario activo no tiene cuenta contable"));
            ledger.postTransfer(UUID.randomUUID(), "TRANSFER", reference, payerAccount, payeeAccount, amount);
            // Evento de activacion del pagador para MAU (HU-02 CA6), sin datos personales.
            outbox.append("PAYMENT", paymentId, "PAYMENT_SUCCEEDED", String.format(
                    "{\"paymentId\":\"%s\",\"payerId\":\"%s\",\"amount\":\"%s\",\"currency\":\"%s\"}",
                    paymentId, payerId, amount.amount().toPlainString(), amount.currency()));
        }
        payments.insertAttempt(paymentId, payerId, idempotencyKey, "ORIGINAL", status,
                ctx.requestId(), ctx.correlationId());

        PaymentRow created = payments.findByKey(payerId, idempotencyKey).orElseThrow();
        return new ConfirmResult(toPayment(created), false);
    }

    /** Consulta del resultado original tras un timeout o desconexion del cliente. */
    @Transactional(readOnly = true)
    public Optional<PaymentView> resultOfIntent(UUID payerId, UUID intentId) {
        IntentRow intent = payments.findIntent(intentId)
                .filter(i -> i.payerId().equals(payerId))
                .orElseThrow(() -> new WalletException(Kind.NOT_FOUND, "INTENT_NOT_FOUND",
                        "No encontramos ese intento de pago."));
        return payments.findByIntent(intent.id(), payerId).map(PaymentService::toPayment);
    }

    @Transactional(readOnly = true)
    public PaymentView get(UUID userId, UUID paymentId) {
        return payments.findVisible(paymentId, userId).map(PaymentService::toPayment)
                .orElseThrow(() -> new WalletException(Kind.NOT_FOUND, "PAYMENT_NOT_FOUND",
                        "No encontramos ese pago."));
    }

    @Transactional(readOnly = true)
    public List<MovementView> history(UUID userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        int offset = Math.max(page, 0) * safeSize;
        return payments.history(userId, safeSize, offset).stream().map(PaymentService::toMovement).toList();
    }

    /** Salida para conciliacion: distingue intentos originales de reintentos duplicados (HU-04 CA4). */
    @Transactional(readOnly = true)
    public List<AttemptView> attempts(UUID payerId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        int offset = Math.max(page, 0) * safeSize;
        return payments.attempts(payerId, safeSize, offset).stream().map(PaymentService::toAttempt).toList();
    }

    public Money balanceOf(UUID userId) {
        requireActive(userId);
        UUID account = ledger.walletAccountOf(userId).orElseThrow(
                () -> new WalletException(Kind.CONFLICT, "WALLET_NOT_ACTIVE", "Activa tu wallet para continuar."));
        return ledger.balance(account, props.payment().currency());
    }

    private User requireActive(UUID userId) {
        User user = users.findById(userId).orElseThrow(
                () -> new WalletException(Kind.UNAUTHENTICATED, "UNAUTHENTICATED", "Inicia sesión para continuar."));
        if (!user.isActive()) {
            throw new WalletException(Kind.CONFLICT, "WALLET_NOT_ACTIVE",
                    "Completa tu registro para activar la wallet y pagar.");
        }
        return user;
    }

    private Money validateAmount(BigDecimal raw) {
        if (raw.stripTrailingZeros().scale() > Money.SCALE) {
            throw new WalletException(Kind.VALIDATION, "VALIDATION_ERROR",
                    "Revisa el monto ingresado.", List.of(new FieldIssue("amount", "Usa como máximo 2 decimales.")));
        }
        Money amount = Money.of(raw, props.payment().currency());
        if (!amount.isPositive()) {
            throw new WalletException(Kind.VALIDATION, "VALIDATION_ERROR",
                    "Revisa el monto ingresado.", List.of(new FieldIssue("amount", "El monto debe ser mayor a cero.")));
        }
        Money max = Money.of(props.payment().maxAmount(), props.payment().currency());
        if (amount.isGreaterThan(max)) {
            throw new WalletException(Kind.UNPROCESSABLE, "AMOUNT_ABOVE_LIMIT",
                    "El monto máximo por pago es S/ " + max.amount().toPlainString() + ".",
                    List.of(new FieldIssue("amount", "Ingresa un monto menor o igual a S/ "
                            + max.amount().toPlainString() + ".")));
        }
        return amount;
    }

    private String newReference() {
        String reference;
        do {
            byte[] bytes = new byte[4];
            random.nextBytes(bytes);
            String hex = HexFormat.of().withUpperCase().formatHex(bytes);
            reference = "TX-" + hex.substring(0, 4) + "-" + hex.substring(4);
        } while (payments.referenceExists(reference));
        return reference;
    }

    private static PaymentView toPayment(PaymentRow row) {
        return new PaymentView(row.id(), row.reference(), row.status(), row.failureCode(),
                messageOf(row.status(), row.failureCode()), row.amount().setScale(2).toPlainString(), row.currency(),
                row.payeeName(), row.createdAt());
    }

    private static MovementView toMovement(MovementRow row) {
        return new MovementView(row.paymentId(), row.reference(), row.direction(), row.counterpartName(),
                row.amount().setScale(2).toPlainString(), row.currency(), row.status(), row.createdAt());
    }

    private static AttemptView toAttempt(AttemptRow row) {
        return new AttemptView(row.id(), row.paymentId(), row.reference(), row.kind(), row.outcome(),
                row.requestId(), row.correlationId(), row.createdAt());
    }

    private static String messageOf(String status, String failureCode) {
        if ("SUCCESS".equals(status)) {
            return "Pago exitoso.";
        }
        return switch (failureCode == null ? "" : failureCode) {
            case "INTENT_EXPIRED" -> "El tiempo de espera expiró. No se hizo ningún cobro.";
            case "INSUFFICIENT_FUNDS" -> "Saldo insuficiente. No se hizo ningún cobro.";
            case "DESTINATION_UNAVAILABLE" -> "El destino ya no está disponible. No se hizo ningún cobro.";
            default -> "No pudimos completar el pago. No se hizo ningún cobro.";
        };
    }
}
