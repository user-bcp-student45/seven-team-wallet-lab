package com.seventeam.wallet.identity.adapter.out;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Eventos de onboarding para analitica del MVP (HU-01 CA4). No contienen datos personales. */
@Repository
public class OnboardingEventRepository {

    private final JdbcClient jdbc;

    public OnboardingEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID userId, String eventType, Integer step, String correlationId) {
        jdbc.sql("""
                insert into onboarding_event (user_id, event_type, step, correlation_id)
                values (:user, :type, :step, :corr)""")
                .param("user", userId).param("type", eventType).param("step", step).param("corr", correlationId)
                .update();
    }
}
