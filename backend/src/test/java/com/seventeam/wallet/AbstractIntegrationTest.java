package com.seventeam.wallet;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seventeam.wallet.identity.adapter.out.ContactRepository;
import com.seventeam.wallet.identity.adapter.out.UserRepository;
import com.seventeam.wallet.identity.application.OnboardingService;
import com.seventeam.wallet.ledger.LedgerService;
import com.seventeam.wallet.shared.Money;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/** Pruebas contra PostgreSQL real (base wallet_test): ledger, restricciones y concurrencia no se simulan. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {

    protected static final String PASSWORD = "Clave-Segura-2026";

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected UserRepository users;
    @Autowired protected ContactRepository contacts;
    @Autowired protected LedgerService ledger;
    @Autowired protected OnboardingService onboarding;
    @Autowired protected PasswordEncoder encoder;
    @Autowired protected TransactionTemplate tx;

    @BeforeEach
    void resetDatabase() {
        jdbc.sql("""
                truncate table payment_attempt, payment, payment_intent, outbox_event, contact, ledger_entry,
                               ledger_transaction, account, onboarding_event, app_user restart identity cascade""")
                .update();
        ledger.ensureSystemAccount("PEN");
    }

    /** Usuario activo con wallet y saldo ficticio, creado directamente en la base. */
    protected UUID activeUser(String name, String email, String dni, String phone, boolean demoContact,
                              String balance) {
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            users.insertActiveDemo(id, email, encoder.encode(PASSWORD), name, dni, phone, demoContact);
            UUID account = ledger.createWalletAccount(id, "PEN");
            if (new BigDecimal(balance).signum() > 0) {
                onboarding.seedBalance(account, Money.of(new BigDecimal(balance), "PEN"));
            }
        });
        return id;
    }

    protected void addContact(UUID owner, UUID contact) {
        jdbc.sql("insert into contact (owner_user_id, contact_user_id) values (:o, :c)")
                .param("o", owner).param("c", contact).update();
    }

    protected MockHttpSession login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    protected JsonNode createIntent(MockHttpSession session, UUID contactId, String amount) throws Exception {
        MvcResult result = mvc.perform(post("/api/payments/intents").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("contactId", contactId, "amount", amount))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    protected MvcResult confirm(MockHttpSession session, JsonNode intent, String key) throws Exception {
        return mvc.perform(post("/api/payments").session(session).with(csrf())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("intentId", intent.get("intentId").asText()))))
                .andReturn();
    }

    protected Money balanceOf(UUID userId) {
        return ledger.balance(ledger.walletAccountOf(userId).orElseThrow(), "PEN");
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    protected MvcResult getJson(MockHttpSession session, String url) throws Exception {
        return mvc.perform(get(url).session(session)).andReturn();
    }
}
