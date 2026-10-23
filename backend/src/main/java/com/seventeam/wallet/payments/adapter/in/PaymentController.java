package com.seventeam.wallet.payments.adapter.in;

import com.seventeam.wallet.payments.application.PaymentService;
import com.seventeam.wallet.payments.application.PaymentService.AttemptView;
import com.seventeam.wallet.payments.application.PaymentService.ConfirmResult;
import com.seventeam.wallet.payments.application.PaymentService.IntentView;
import com.seventeam.wallet.payments.application.PaymentService.MovementView;
import com.seventeam.wallet.payments.application.PaymentService.PaymentView;
import com.seventeam.wallet.shared.AuthenticatedUser;
import com.seventeam.wallet.shared.CorrelationFilter;
import com.seventeam.wallet.shared.WalletException;
import com.seventeam.wallet.shared.WalletException.Kind;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
class PaymentController {

    record IntentRequest(
            @NotNull(message = "Elige un contacto.") UUID contactId,
            @NotNull(message = "Ingresa el monto.") BigDecimal amount) {}

    record ConfirmRequest(@NotNull(message = "Falta el intento de pago.") UUID intentId) {}

    private final PaymentService payments;

    PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/intents")
    @ResponseStatus(HttpStatus.CREATED)
    IntentView createIntent(@AuthenticationPrincipal AuthenticatedUser principal,
                            @Valid @RequestBody IntentRequest body) {
        return payments.createIntent(principal.userId(), body.contactId(), body.amount());
    }

    @PostMapping
    ResponseEntity<PaymentView> confirm(@AuthenticationPrincipal AuthenticatedUser principal,
                                        @RequestHeader("Idempotency-Key") String idempotencyKey,
                                        @Valid @RequestBody ConfirmRequest body, HttpServletRequest request) {
        ConfirmResult result = payments.confirm(principal.userId(), body.intentId(), idempotencyKey,
                CorrelationFilter.contextOf(request));
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.replayed()) {
            response.header("Idempotent-Replay", "true");
        }
        return response.body(result.payment());
    }

    @GetMapping("/intents/{intentId}/result")
    PaymentView resultOfIntent(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID intentId) {
        return payments.resultOfIntent(principal.userId(), intentId).orElseThrow(
                () -> new WalletException(Kind.NOT_FOUND, "NO_RESULT_YET", "El intento aún no tiene resultado."));
    }

    @GetMapping
    List<MovementView> history(@AuthenticationPrincipal AuthenticatedUser principal,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return payments.history(principal.userId(), page, size);
    }

    @GetMapping("/attempts")
    List<AttemptView> attempts(@AuthenticationPrincipal AuthenticatedUser principal,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return payments.attempts(principal.userId(), page, size);
    }

    @GetMapping("/{paymentId}")
    PaymentView get(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID paymentId) {
        return payments.get(principal.userId(), paymentId);
    }
}
