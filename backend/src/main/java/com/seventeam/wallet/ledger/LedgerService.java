package com.seventeam.wallet.ledger;

import com.seventeam.wallet.shared.Money;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Ledger de doble partida: asientos inmutables, NUMERIC exacto y moneda explicita. */
@Service
public class LedgerService {

    public static final UUID SYSTEM_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final JdbcClient jdbc;

    public LedgerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void ensureSystemAccount(String currency) {
        jdbc.sql("insert into account (id, type, currency) values (:id, 'SYSTEM', :cur) on conflict do nothing")
                .param("id", SYSTEM_ACCOUNT_ID).param("cur", currency).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createWalletAccount(UUID ownerId, String currency) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into account (id, owner_user_id, type, currency) values (:id, :owner, 'WALLET', :cur)")
                .param("id", id).param("owner", ownerId).param("cur", currency).update();
        return id;
    }

    public Optional<UUID> walletAccountOf(UUID ownerId) {
        return jdbc.sql("select id from account where owner_user_id = :owner and type = 'WALLET'")
                .param("owner", ownerId).query(UUID.class).optional();
    }

    /** Bloquea la cuenta para serializar movimientos concurrentes del mismo pagador. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID accountId) {
        jdbc.sql("select id from account where id = :id for update").param("id", accountId).query(UUID.class).single();
    }

    public Money balance(UUID accountId, String currency) {
        BigDecimal value = jdbc.sql("""
                select coalesce(sum(case direction when 'CREDIT' then amount else -amount end), 0)
                  from ledger_entry where account_id = :id and currency = :cur""")
                .param("id", accountId).param("cur", currency)
                .query(BigDecimal.class).single();
        return Money.of(value, currency);
    }

    /** Un debito y un credito iguales; la base verifica el balance al confirmar. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void postTransfer(UUID transactionId, String kind, String reference, UUID debitAccount,
                             UUID creditAccount, Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("El importe debe ser positivo");
        }
        jdbc.sql("insert into ledger_transaction (id, kind, reference) values (:id, :kind, :ref)")
                .param("id", transactionId).param("kind", kind).param("ref", reference).update();
        insertEntry(transactionId, debitAccount, "DEBIT", amount);
        insertEntry(transactionId, creditAccount, "CREDIT", amount);
    }

    private void insertEntry(UUID transactionId, UUID accountId, String direction, Money amount) {
        jdbc.sql("""
                insert into ledger_entry (transaction_id, account_id, direction, amount, currency)
                values (:tx, :acc, :dir, :amount, :cur)""")
                .param("tx", transactionId).param("acc", accountId).param("dir", direction)
                .param("amount", amount.amount()).param("cur", amount.currency()).update();
    }
}
