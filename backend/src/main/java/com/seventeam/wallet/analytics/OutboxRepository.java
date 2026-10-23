package com.seventeam.wallet.analytics;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Outbox transaccional: se escribe en la misma transaccion que el cambio de dominio. */
@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** payloadJson no debe contener PII ni tokens. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String aggregateType, UUID aggregateId, String eventType, String payloadJson) {
        jdbc.sql("""
                insert into outbox_event (aggregate_type, aggregate_id, event_type, payload)
                values (:type, :id, :event, cast(:payload as jsonb))""")
                .param("type", aggregateType).param("id", aggregateId).param("event", eventType)
                .param("payload", payloadJson).update();
    }
}
