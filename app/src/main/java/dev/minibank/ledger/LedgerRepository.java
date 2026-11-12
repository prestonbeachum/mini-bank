package dev.minibank.ledger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Instant insertTransaction(UUID id, String type, String currency, String reference) {
        return jdbc.sql("""
				INSERT INTO transactions (id, type, currency, reference) VALUES (:id, :type, :currency, :reference)
				RETURNING posted_at
				""")
                .param("id", id)
                .param("type", type)
                .param("currency", currency)
                .param("reference", reference)
                .query((rs, n) ->
                        rs.getObject("posted_at", OffsetDateTime.class).toInstant())
                .single();
    }

    public void insertPosting(UUID transactionId, UUID accountId, long amountMinor) {
        jdbc.sql("INSERT INTO postings (transaction_id, account_id, amount_minor) VALUES (:txn, :account, :amount)")
                .param("txn", transactionId)
                .param("account", accountId)
                .param("amount", amountMinor)
                .update();
    }

    /** Written in the ledger transaction, so the event exists if and only if the money moved. */
    public void insertOutboxEvent(UUID eventId, String eventType, String payloadJson) {
        jdbc.sql("""
				INSERT INTO outbox_events (event_id, event_type, payload) VALUES (:id, :type, CAST(:payload AS jsonb))
				""")
                .param("id", eventId)
                .param("type", eventType)
                .param("payload", payloadJson)
                .update();
    }

    public Optional<Transaction> find(UUID id) {
        List<Transaction.Posting> postings = jdbc.sql(
                        "SELECT account_id, amount_minor FROM postings WHERE transaction_id = :id ORDER BY id")
                .param("id", id)
                .query((rs, n) ->
                        new Transaction.Posting(rs.getObject("account_id", UUID.class), rs.getLong("amount_minor")))
                .list();
        return jdbc.sql("SELECT id, type, status, currency, reference, posted_at FROM transactions WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new Transaction(
                        rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        rs.getString("status"),
                        rs.getString("currency").trim(),
                        rs.getString("reference"),
                        postings,
                        rs.getObject("posted_at", OffsetDateTime.class).toInstant()))
                .optional();
    }
}
