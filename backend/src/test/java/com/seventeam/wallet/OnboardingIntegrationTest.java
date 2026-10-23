package com.seventeam.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/** HU-01: criterios 1 a 4 y controles de identidad. */
class OnboardingIntegrationTest extends AbstractIntegrationTest {

    private MockHttpSession register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/onboarding/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentStep").value(2))
                .andExpect(jsonPath("$.totalSteps").value(4))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MvcResult postJson(MockHttpSession session, String url, Map<String, ?> payload) throws Exception {
        return mvc.perform(post(url).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload))).andReturn();
    }

    @Test
    void guidedFlowActivatesTheWalletAndRecordsEvents() throws Exception {
        UUID carlos = activeUser("Carlos Quispe", "carlos@demo.kipu.example", "70000001", "912345987", true, "200.00");
        MockHttpSession session = register("maria@demo.kipu.example");

        // CA1: secuencia de pasos y paso actual siempre visibles.
        JsonNode status = body(getJson(session, "/api/onboarding"));
        assertThat(status.get("steps")).hasSize(4);
        assertThat(status.get("steps").get(1).get("state").asText()).isEqualTo("CURRENT");

        // CA2: datos invalidos bloquean el avance con mensaje accionable.
        MvcResult invalid = postJson(session, "/api/onboarding/personal-data",
                Map.of("fullName", "María Fernanda", "documentNumber", "1234"));
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(invalid).get("fieldErrors").get(0).get("message").asText())
                .isEqualTo("El DNI debe tener 8 dígitos.");

        // No se puede saltar pasos.
        MvcResult early = postJson(session, "/api/onboarding/activate", Map.of("acceptTerms", true));
        assertThat(early.getResponse().getStatus()).isEqualTo(409);

        assertThat(postJson(session, "/api/onboarding/personal-data",
                Map.of("fullName", "María Fernanda", "documentNumber", "45678912")).getResponse().getStatus())
                .isEqualTo(200);
        JsonNode code = body(postJson(session, "/api/onboarding/phone/code", Map.of("phone", "987654321")));
        assertThat(postJson(session, "/api/onboarding/phone/verify", Map.of("code", "000000")).getResponse().getStatus())
                .isEqualTo(422);
        assertThat(postJson(session, "/api/onboarding/phone/verify", Map.of("code", code.get("demoCode").asText()))
                .getResponse().getStatus()).isEqualTo(200);

        // CA3: wallet activa con acceso al punto de entrada de pagos.
        JsonNode active = body(postJson(session, "/api/onboarding/activate", Map.of("acceptTerms", true)));
        assertThat(active.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body(getJson(session, "/api/wallet")).get("balance").asText()).isEqualTo("100.00");
        assertThat(body(getJson(session, "/api/contacts")).get(0).get("id").asText()).isEqualTo(carlos.toString());

        // CA4: eventos de inicio, cada paso y cierre.
        var events = jdbc.sql("select event_type || coalesce(':' || step, '') from onboarding_event order by id")
                .query(String.class).list();
        assertThat(events).containsExactly("ONBOARDING_STARTED", "STEP_COMPLETED:1", "STEP_COMPLETED:2",
                "STEP_COMPLETED:3", "STEP_COMPLETED:4", "ONBOARDING_COMPLETED");
        assertThat(jdbc.sql("select count(*) from outbox_event where event_type = 'WALLET_ACTIVATED'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void walletNotActiveCannotPay() throws Exception {
        MockHttpSession session = register("nuevo@demo.kipu.example");
        mvc.perform(get("/api/wallet").session(session)).andExpect(status().isConflict());
    }

    @Test
    void passwordsAreStoredAsArgon2Hash() throws Exception {
        register("hash@demo.kipu.example");
        String hash = jdbc.sql("select password_hash from app_user").query(String.class).single();
        assertThat(hash).startsWith("$argon2id$").doesNotContain(PASSWORD);
    }

    @Test
    void duplicateEmailIsRejectedWithoutDetails() throws Exception {
        register("dup@demo.kipu.example");
        mvc.perform(post("/api/onboarding/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "dup@demo.kipu.example", "password", PASSWORD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGISTRATION_REJECTED"));
    }

    @Test
    void unknownUserAndWrongPasswordLookTheSameAndAccountLocks() throws Exception {
        activeUser("Ana Torres", "ana@demo.kipu.example", "70000002", "912345111", false, "10.00");
        String unknown = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "nadie@demo.kipu.example", "password", "x"))))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String wrong = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "ana@demo.kipu.example", "password", "mala"))))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(unknown).get("message")).isEqualTo(json.readTree(wrong).get("message"));

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("email", "ana@demo.kipu.example", "password", "mala"))));
        }
        // Con la clave correcta, la cuenta bloqueada tampoco inicia sesion.
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "ana@demo.kipu.example", "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointsRequireSessionAndCsrf() throws Exception {
        mvc.perform(get("/api/wallet")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/onboarding/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "csrf@demo.kipu.example", "password", PASSWORD))))
                .andExpect(status().isForbidden());
    }
}
