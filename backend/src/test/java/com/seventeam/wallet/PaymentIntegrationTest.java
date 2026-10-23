package com.seventeam.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.seventeam.wallet.payments.application.PaymentService;
import com.seventeam.wallet.shared.RequestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/** HU-02 (confirmacion de pago) y HU-04 (idempotencia) con ledger real en PostgreSQL. */
class PaymentIntegrationTest extends AbstractIntegrationTest {

    @Autowired PaymentService payments;

    private UUID maria;
    private UUID carlos;
    private MockHttpSession mariaSession;

    @BeforeEach
    void fixtures() throws Exception {
        carlos = activeUser("Carlos Quispe", "carlos@demo.kipu.example", "70000001", "912345987", true, "50.00");
        maria = activeUser("María Fernández", "maria@demo.kipu.example", "70000004", "912345333", false, "100.00");
        addContact(maria, carlos);
        mariaSession = login("maria@demo.kipu.example");
    }

    @Test
    void reviewShowsVerifiedDestinationAndAmountBeforeConfirming() throws Exception {
        JsonNode intent = createIntent(mariaSession, carlos, "20.00");
        assertThat(intent.get("destination").get("displayName").asText()).isEqualTo("Carlos Quispe");
        assertThat(intent.get("destination").get("verified").asBoolean()).isTrue();
        assertThat(intent.get("amount").asText()).isEqualTo("20.00");
        assertThat(intent.get("fee").asText()).isEqualTo("0.00");
        // Crear el intento no mueve fondos.
        assertThat(balanceOf(maria).amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void successfulPaymentIsTerminalPersistedAndBalancedInTheLedger() throws Exception {
        JsonNode intent = createIntent(mariaSession, carlos, "20.00");
        MvcResult result = confirm(mariaSession, intent, intent.get("idempotencyKey").asText());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode payment = body(result);
        assertThat(payment.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(payment.get("reference").asText()).matches("TX-[0-9A-F]{4}-[0-9A-F]{4}");
        assertThat(balanceOf(maria).amount()).isEqualByComparingTo("80.00");
        assertThat(balanceOf(carlos).amount()).isEqualByComparingTo("70.00");

        // Doble partida: cada transaccion balancea debitos y creditos.
        int unbalanced = jdbc.sql("""
                select count(*) from (select transaction_id from ledger_entry group by transaction_id
                having sum(case direction when 'DEBIT' then amount else -amount end) <> 0) t""")
                .query(Integer.class).single();
        assertThat(unbalanced).isZero();

        // CA3: referencia persistida en el historial, tambien para el destinatario.
        JsonNode history = body(getJson(mariaSession, "/api/payments"));
        assertThat(history.get(0).get("reference").asText()).isEqualTo(payment.get("reference").asText());
        assertThat(history.get(0).get("direction").asText()).isEqualTo("SENT");
        MockHttpSession carlosSession = login("carlos@demo.kipu.example");
        assertThat(body(getJson(carlosSession, "/api/payments")).get(0).get("direction").asText())
                .isEqualTo("RECEIVED");

        // CA6: evento de activacion del pagador sin datos personales.
        assertThat(jdbc.sql("select payload::text from outbox_event where event_type = 'PAYMENT_SUCCEEDED'")
                .query(String.class).single()).doesNotContain("Carlos", "maria@");
    }

    @Test
    void repeatedRequestWithTheSameKeyReturnsTheOriginalResultWithoutAnotherDebit() throws Exception {
        JsonNode intent = createIntent(mariaSession, carlos, "20.00");
        String key = intent.get("idempotencyKey").asText();
        JsonNode first = body(confirm(mariaSession, intent, key));
        MvcResult retry = confirm(mariaSession, intent, key);

        assertThat(retry.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(body(retry).get("paymentId").asText()).isEqualTo(first.get("paymentId").asText());
        assertThat(body(retry).get("reference").asText()).isEqualTo(first.get("reference").asText());
        assertThat(balanceOf(maria).amount()).isEqualByComparingTo("80.00");
        assertThat(jdbc.sql("select count(*) from payment").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from ledger_transaction where kind = 'TRANSFER'")
                .query(Integer.class).single()).isEqualTo(1);

        // CA3 y CA4 de HU-04: auditoria con ids y distincion original/duplicado.
        JsonNode attempts = body(getJson(mariaSession, "/api/payments/attempts"));
        assertThat(attempts).hasSize(2);
        List<String> kinds = new ArrayList<>();
        attempts.forEach(a -> kinds.add(a.get("kind").asText()));
        assertThat(kinds).containsExactlyInAnyOrder("ORIGINAL", "DUPLICATE");
        attempts.forEach(a -> {
            assertThat(a.get("requestId").asText()).isNotBlank();
            assertThat(a.get("correlationId").asText()).isNotBlank();
        });
    }

    @Test
    void aKeyThatDoesNotMatchTheIntentIsRejectedWithoutMovingFunds() throws Exception {
        JsonNode intent = createIntent(mariaSession, carlos, "20.00");
        MvcResult result = confirm(mariaSession, intent, "0".repeat(64));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(balanceOf(maria).amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void insufficientFundsEndsAsFailedWithoutCharging() throws Exception {
        JsonNode intent = createIntent(mariaSession, carlos, "150.00");
        JsonNode payment = body(confirm(mariaSession, intent, intent.get("idempotencyKey").asText()));
        assertThat(payment.get("status").asText()).isEqualTo("FAILED");
        assertThat(payment.get("failureCode").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(payment.get("message").asText()).contains("No se hizo ningún cobro");
        assertThat(balanceOf(maria).amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void anExpiredIntentStaysExpiredEvenIfAnotherResponseArrivesLate() throws Exception {
        JsonNode intent = createIntent(mariaSession, carlos, "20.00");
        jdbc.sql("update payment_intent set expires_at = now() - interval '1 minute' where id = :id")
                .param("id", UUID.fromString(intent.get("intentId").asText())).update();

        String key = intent.get("idempotencyKey").asText();
        JsonNode first = body(confirm(mariaSession, intent, key));
        assertThat(first.get("status").asText()).isEqualTo("EXPIRED");

        JsonNode late = body(confirm(mariaSession, intent, key));
        assertThat(late.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(late.get("paymentId").asText()).isEqualTo(first.get("paymentId").asText());
        assertThat(balanceOf(maria).amount()).isEqualByComparingTo("100.00");
        assertThat(jdbc.sql("select count(*) from ledger_transaction where kind = 'TRANSFER'")
                .query(Integer.class).single()).isZero();

        // Tras un fallo se puede reintentar con un intento nuevo, sin cobros duplicados.
        JsonNode retryIntent = createIntent(mariaSession, carlos, "20.00");
        assertThat(retryIntent.get("idempotencyKey").asText()).isNotEqualTo(key);
        assertThat(body(confirm(mariaSession, retryIntent, retryIntent.get("idempotencyKey").asText()))
                .get("status").asText()).isEqualTo("SUCCESS");
        assertThat(balanceOf(maria).amount()).isEqualByComparingTo("80.00");
    }

    @Test
    void resultCanBeRecoveredByIntentAfterAnUncertainResponse() throws Exception {
        JsonNode intent = createIntent(mariaSession, carlos, "20.00");
        String url = "/api/payments/intents/" + intent.get("intentId").asText() + "/result";
        assertThat(getJson(mariaSession, url).getResponse().getStatus()).isEqualTo(404);
        confirm(mariaSession, intent, intent.get("idempotencyKey").asText());
        assertThat(body(getJson(mariaSession, url)).get("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateOnePayment() throws Exception {
        JsonNode intent = createIntent(mariaSession, carlos, "20.00");
        UUID intentId = UUID.fromString(intent.get("intentId").asText());
        String key = intent.get("idempotencyKey").asText();

        List<Callable<PaymentService.ConfirmResult>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> payments.confirm(maria, intentId, key, new RequestContext(UUID.randomUUID().toString(), "corr-test-1")));
        }
        List<PaymentService.ConfirmResult> results = runConcurrently(calls);

        assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
        assertThat(results).extracting(r -> r.payment().paymentId()).containsOnly(results.get(0).payment().paymentId());
        assertThat(balanceOf(maria).amount()).isEqualByComparingTo("80.00");
        assertThat(jdbc.sql("select count(*) from payment").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentPaymentsNeverOverdrawTheWallet() throws Exception {
        List<Callable<PaymentService.ConfirmResult>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            JsonNode intent = createIntent(mariaSession, carlos, "40.00");
            UUID intentId = UUID.fromString(intent.get("intentId").asText());
            String key = intent.get("idempotencyKey").asText();
            calls.add(() -> payments.confirm(maria, intentId, key, new RequestContext(UUID.randomUUID().toString(), "corr-test-2")));
        }
        List<PaymentService.ConfirmResult> results = runConcurrently(calls);

        long succeeded = results.stream().filter(r -> "SUCCESS".equals(r.payment().status())).count();
        assertThat(succeeded).isEqualTo(2);
        assertThat(balanceOf(maria).amount()).isEqualByComparingTo("20.00");
        assertThat(balanceOf(carlos).amount()).isEqualByComparingTo("130.00");
    }

    @Test
    void usersCannotUseOrReadOtherUsersPayments() throws Exception {
        UUID ana = activeUser("Ana Torres", "ana@demo.kipu.example", "70000002", "912345111", false, "100.00");
        addContact(ana, carlos);
        MockHttpSession anaSession = login("ana@demo.kipu.example");

        JsonNode intent = createIntent(mariaSession, carlos, "20.00");
        // Ana no puede confirmar el intento de Maria.
        assertThat(confirm(anaSession, intent, intent.get("idempotencyKey").asText()).getResponse().getStatus())
                .isEqualTo(404);
        JsonNode payment = body(confirm(mariaSession, intent, intent.get("idempotencyKey").asText()));
        // Ni leer su pago.
        assertThat(getJson(anaSession, "/api/payments/" + payment.get("paymentId").asText()).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(body(getJson(anaSession, "/api/payments"))).isEmpty();
        assertThat(balanceOf(ana).amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void onlyContactsCanBePaidAndAmountsAreValidated() throws Exception {
        UUID stranger = activeUser("Luis Mendoza", "luis@demo.kipu.example", "70000003", "912345222", false, "0");
        mvcIntent(stranger, "20.00", 422, "DESTINATION_NOT_CONTACT");
        mvcIntent(carlos, "0.00", 400, "VALIDATION_ERROR");
        mvcIntent(carlos, "-5.00", 400, "VALIDATION_ERROR");
        mvcIntent(carlos, "10.005", 400, "VALIDATION_ERROR");
        mvcIntent(carlos, "500.01", 422, "AMOUNT_ABOVE_LIMIT");
    }

    private void mvcIntent(UUID contact, String amount, int expectedStatus, String expectedCode) throws Exception {
        MvcResult result = mvc.perform(post("/api/payments/intents").session(mariaSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("contactId", contact, "amount", amount))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        assertThat(body(result).get("code").asText()).isEqualTo(expectedCode);
    }

    private <T> List<T> runConcurrently(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
