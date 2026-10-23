package com.seventeam.wallet.payments.adapter.out;

import com.seventeam.wallet.shared.Hashing;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {

    public record IntentRow(UUID id, UUID payerId, UUID destinationId, BigDecimal amount, String currency,
                            String idempotencyKey, Instant expiresAt) {}

    public record PaymentRow(UUID id, String reference, UUID intentId, UUID payerId, UUID payeeId, String payeeName,
                             BigDecimal amount, String currency, String status, String failureCode,
                             String fingerprint, Instant createdAt) {}

    public record MovementRow(UUID paymentId, String reference, String direction, String counterpartName,
                              BigDecimal amount, String currency, String status, Instant createdAt) {}

    public record AttemptRow(UUID id, UUID paymentId, String reference, String kind, String outcome,
                             String requestId, String correlationId, Instant createdAt) {}

    private static final String PAYMENT_SELECT = """
            select p.id, p.reference, p.intent_id, p.payer_id, p.payee_id, u.full_name as payee_name, p.amount,
                   p.currency, p.status, p.failure_code, p.fingerprint, p.created_at
              from payment p join app_user u on u.id = p.payee_id""";

    private static final RowMapper<PaymentRow> PAYMENT_MAPPER = (rs, n) -> new PaymentRow(
            rs.getObject("id", UUID.class), rs.getString("reference"), rs.getObject("intent_id", UUID.class),
            rs.getObject("payer_id", UUID.class), rs.getObject("payee_id", UUID.class), rs.getString("payee_name"),
            rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("status"),
            rs.getString("failure_code"), rs.getString("fingerprint"), instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertIntent(IntentRow intent, Instant createdAt) {
        jdbc.sql("""
                insert into payment_intent (id, payer_id, destination_user_id, amount, currency, idempotency_key,
                                            created_at, expires_at)
                values (:id, :payer, :dest, :amount, :cur, :key, :created, :expires)""")
                .param("id", intent.id()).param("payer", intent.payerId()).param("dest", intent.destinationId())
                .param("amount", intent.amount()).param("cur", intent.currency()).param("key", intent.idempotencyKey())
                .param("created", Hashing.utc(createdAt)).param("expires", Hashing.utc(intent.expiresAt()))
                .update();
    }

    public Optional<IntentRow> findIntent(UUID id) {
        return jdbc.sql("""
                select id, payer_id, destination_user_id, amount, currency, idempotency_key, expires_at
                  from payment_intent where id = :id""")
                .param("id", id)
                .query((rs, n) -> new IntentRow(rs.getObject("id", UUID.class), rs.getObject("payer_id", UUID.class),
                        rs.getObject("destination_user_id", UUID.class), rs.getBigDecimal("amount"),
                        rs.getString("currency"), rs.getString("idempotency_key"), instant(rs, "expires_at")))
                .optional();
    }

    public Optional<PaymentRow> findByKey(UUID payerId, String key) {
        return jdbc.sql(PAYMENT_SELECT + " where p.payer_id = :payer and p.idempotency_key = :key")
                .param("payer", payerId).param("key", key).query(PAYMENT_MAPPER).optional();
    }

    public Optional<PaymentRow> findByIntent(UUID intentId, UUID payerId) {
        return jdbc.sql(PAYMENT_SELECT + " where p.intent_id = :intent and p.payer_id = :payer")
                .param("intent", intentId).param("payer", payerId).query(PAYMENT_MAPPER).optional();
    }

    /** Un pago solo es visible para el pagador, o para el destinatario cuando fue exitoso. */
    public Optional<PaymentRow> findVisible(UUID paymentId, UUID userId) {
        return jdbc.sql(PAYMENT_SELECT
                + " where p.id = :id and (p.payer_id = :user or (p.payee_id = :user and p.status = 'SUCCESS'))")
                .param("id", paymentId).param("user", userId).query(PAYMENT_MAPPER).optional();
    }

    public boolean referenceExists(String reference) {
        return jdbc.sql("select count(*) from payment where reference = :ref").param("ref", reference)
                .query(Integer.class).single() > 0;
    }

    public void insertPayment(UUID id, String reference, UUID intentId, UUID payerId, UUID payeeId, BigDecimal amount,
                              String currency, String status, String failureCode, String key, String fingerprint,
                              Instant createdAt) {
        jdbc.sql("""
                insert into payment (id, reference, intent_id, payer_id, payee_id, amount, currency, status,
                                     failure_code, idempotency_key, fingerprint, created_at)
                values (:id, :ref, :intent, :payer, :payee, :amount, :cur, :status, :failure, :key, :fp, :created)""")
                .param("id", id).param("ref", reference).param("intent", intentId).param("payer", payerId)
                .param("payee", payeeId).param("amount", amount).param("cur", currency).param("status", status)
                .param("failure", failureCode).param("key", key).param("fp", fingerprint)
                .param("created", Hashing.utc(createdAt)).update();
    }

    public void insertAttempt(UUID paymentId, UUID payerId, String key, String kind, String outcome,
                              String requestId, String correlationId) {
        jdbc.sql("""
                insert into payment_attempt (id, payment_id, payer_id, idempotency_key, kind, outcome, request_id,
                                             correlation_id)
                values (:id, :payment, :payer, :key, :kind, :outcome, :req, :corr)""")
                .param("id", UUID.randomUUID()).param("payment", paymentId).param("payer", payerId)
                .param("key", key).param("kind", kind).param("outcome", outcome).param("req", requestId)
                .param("corr", correlationId).update();
    }

    public List<MovementRow> history(UUID userId, int limit, int offset) {
        return jdbc.sql("""
                select p.id, p.reference, p.amount, p.currency, p.status, p.created_at,
                       case when p.payer_id = :user then 'SENT' else 'RECEIVED' end as direction,
                       c.full_name as counterpart
                  from payment p
                  join app_user c on c.id = case when p.payer_id = :user then p.payee_id else p.payer_id end
                 where p.payer_id = :user or (p.payee_id = :user and p.status = 'SUCCESS')
                 order by p.created_at desc, p.id
                 limit :limit offset :offset""")
                .param("user", userId).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new MovementRow(rs.getObject("id", UUID.class), rs.getString("reference"),
                        rs.getString("direction"), rs.getString("counterpart"), rs.getBigDecimal("amount"),
                        rs.getString("currency"), rs.getString("status"), instant(rs, "created_at")))
                .list();
    }

    public List<AttemptRow> attempts(UUID payerId, int limit, int offset) {
        return jdbc.sql("""
                select a.id, a.payment_id, p.reference, a.kind, a.outcome, a.request_id, a.correlation_id, a.created_at
                  from payment_attempt a join payment p on p.id = a.payment_id
                 where a.payer_id = :payer
                 order by a.created_at desc, a.id
                 limit :limit offset :offset""")
                .param("payer", payerId).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new AttemptRow(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
                        rs.getString("reference"), rs.getString("kind"), rs.getString("outcome"),
                        rs.getString("request_id"), rs.getString("correlation_id"), instant(rs, "created_at")))
                .list();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
