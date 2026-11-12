package dev.minibank.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final String COLUMNS = """
			id, customer_id, kind, currency, status, balance_minor, holds_minor, overdraft_limit_minor, created_at
			""";

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Account insert(UUID id, UUID customerId, String kind, String currency) {
        return jdbc.sql(
                        "INSERT INTO accounts (id, customer_id, kind, currency) VALUES (:id, :customer, :kind, :currency) "
                                + "RETURNING " + COLUMNS)
                .param("id", id)
                .param("customer", customerId)
                .param("kind", kind)
                .param("currency", currency)
                .query(AccountRepository::map)
                .single();
    }

    public Optional<Account> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id")
                .param("id", id)
                .query(AccountRepository::map)
                .optional();
    }

    /**
     * Locks the rows in ascending id order. Every money move locks this way, so two transfers in
     * opposite directions can never wait on each other in a cycle (build doc section 7).
     */
    public List<Account> lockInIdOrder(Collection<UUID> ids) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                .param("ids", ids)
                .query(AccountRepository::map)
                .list();
    }

    /** Pessimistic strategy: the caller already holds the row lock. Phase 5 benchmarks the alternatives. */
    public void applyDelta(UUID id, long deltaMinor) {
        int updated = jdbc.sql("""
				UPDATE accounts SET balance_minor = balance_minor + :delta, version = version + 1 WHERE id = :id
				""").param("delta", deltaMinor).param("id", id).update();
        if (updated != 1) {
            throw new IllegalStateException("account " + id + " vanished while locked");
        }
    }

    static Account map(ResultSet rs, int row) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("kind"),
                rs.getString("currency").trim(),
                rs.getString("status"),
                rs.getLong("balance_minor"),
                rs.getLong("holds_minor"),
                rs.getLong("overdraft_limit_minor"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
