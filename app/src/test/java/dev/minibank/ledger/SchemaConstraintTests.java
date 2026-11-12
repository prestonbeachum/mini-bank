package dev.minibank.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.minibank.common.Ids;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * The database rejects bad ledger data on its own, even when application code is bypassed.
 * Writes go through the app's own login unless a test says otherwise
 * (build doc section 5: "a bug in the application cannot corrupt the ledger").
 */
class SchemaConstraintTests {

    private final LedgerHarness h = LedgerHarness.get();

    @Test
    void unbalancedTransactionIsRejectedAtCommit() {
        UUID account = h.newAccount(h.newCustomer());
        UUID txn = Ids.newId();
        assertThatThrownBy(() -> h.tx.executeWithoutResult(s -> {
                    insertTransaction(txn, "USD");
                    insertPosting(txn, account, 500);
                    insertPosting(txn, dev.minibank.account.Account.SETTLEMENT, -499);
                }))
                .rootCause()
                .hasMessageContaining("not balanced");
        assertThat(exists(txn)).isFalse();
    }

    @Test
    void balancedTransactionMayInsertPostingsOneAtATime() {
        UUID account = h.newAccount(h.newCustomer());
        UUID txn = Ids.newId();
        h.tx.executeWithoutResult(s -> {
            insertTransaction(txn, "USD");
            insertPosting(txn, account, 500);
            insertPosting(txn, dev.minibank.account.Account.SETTLEMENT, -500);
            h.appJdbc
                    .sql("INSERT INTO outbox_events (event_id, event_type, payload) "
                            + "VALUES (:e, 'transaction.posted', jsonb_build_object('id', CAST(:t AS text)))")
                    .param("e", Ids.newId())
                    .param("t", txn.toString())
                    .update();
            // Balances are fixed up below; this test is only about the deferred trigger.
        });
        assertThat(exists(txn)).isTrue();
        fixBalances(account, 500);
    }

    @Test
    void mixedCurrencyTransactionIsRejected() {
        UUID account = h.newAccount(h.newCustomer());
        UUID txn = Ids.newId();
        assertThatThrownBy(() -> h.tx.executeWithoutResult(s -> {
                    insertTransaction(txn, "EUR");
                    insertPosting(txn, account, 500);
                    insertPosting(txn, dev.minibank.account.Account.SETTLEMENT, -500);
                }))
                .rootCause()
                .hasMessageContaining("mixes currencies");
    }

    @Test
    void postingsCannotBeUpdatedDeletedOrTruncated() {
        // As the owner, so the trigger (not the missing privilege) is what stops it.
        assertThatThrownBy(
                        () -> h.jdbc.sql("UPDATE postings SET amount_minor = 1").update())
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> h.jdbc.sql("DELETE FROM postings").update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> h.jdbc.sql("TRUNCATE postings CASCADE").update())
                .hasMessageContaining("immutable");
    }

    @Test
    void appRoleHasNoPrivilegeToChangePostings() {
        // Second layer of invariant 2: even with the trigger dropped, the app login could not do this.
        assertThatThrownBy(() ->
                        h.appJdbc.sql("UPDATE postings SET amount_minor = 1").update())
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> h.appJdbc.sql("DELETE FROM postings").update())
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> h.appJdbc.sql("TRUNCATE postings").update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void appRoleCannotRewriteHistoryOrSchema() {
        assertThatThrownBy(() -> h.appJdbc
                        .sql("UPDATE transactions SET currency = 'EUR'")
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> h.appJdbc.sql("DELETE FROM transactions").update())
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() ->
                        h.appJdbc.sql("UPDATE accounts SET kind = 'INTERNAL'").update())
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> h.appJdbc
                        .sql("DROP TRIGGER postings_immutable ON postings")
                        .update())
                .rootCause()
                .hasMessageContaining("must be owner");
        assertThatThrownBy(() -> h.appJdbc
                        .sql("SELECT count(*) FROM flyway_schema_history")
                        .query(Long.class)
                        .single())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void zeroAmountPostingIsRejected() {
        UUID txn = Ids.newId();
        assertThatThrownBy(() -> h.tx.executeWithoutResult(s -> {
                    insertTransaction(txn, "USD");
                    insertPosting(txn, dev.minibank.account.Account.SETTLEMENT, 0);
                }))
                .rootCause()
                .hasMessageContaining("postings_amount_minor_check");
    }

    @Test
    void customerAccountCannotGoBelowOverdraftLimit() {
        UUID account = h.newAccount(h.newCustomer());
        h.setOverdraftLimit(account, 1_000);
        assertThatThrownBy(() -> h.appJdbc
                        .sql("UPDATE accounts SET balance_minor = -1001 WHERE id = :id")
                        .param("id", account)
                        .update())
                .hasMessageContaining("accounts_available_funds_check");
        h.appJdbc
                .sql("UPDATE accounts SET balance_minor = -1000 WHERE id = :id")
                .param("id", account)
                .update();
        h.appJdbc
                .sql("UPDATE accounts SET balance_minor = 0 WHERE id = :id")
                .param("id", account)
                .update();
    }

    @Test
    void holdsCannotExceedAvailableFunds() {
        UUID account = h.newAccount(h.newCustomer());
        assertThatThrownBy(() -> h.appJdbc
                        .sql("UPDATE accounts SET holds_minor = 1 WHERE id = :id")
                        .param("id", account)
                        .update())
                .hasMessageContaining("accounts_available_funds_check");
    }

    @Test
    void transactionCanBeReversedAtMostOnce() {
        UUID original = Ids.newId();
        insertTransaction(original, "USD");
        UUID first = Ids.newId();
        insertReversal(first, original);
        assertThatThrownBy(() -> insertReversal(Ids.newId(), original)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void internalAccountsMayGoNegativeButNeedNoCustomer() {
        assertThatThrownBy(() -> h.appJdbc
                        .sql("INSERT INTO accounts (id, kind, currency) VALUES (:id, 'CHECKING', 'USD')")
                        .param("id", Ids.newId())
                        .update())
                .isInstanceOf(DataAccessException.class);
    }

    private void insertTransaction(UUID id, String currency) {
        h.appJdbc
                .sql("INSERT INTO transactions (id, type, currency) VALUES (:id, 'TRANSFER', :c)")
                .param("id", id)
                .param("c", currency)
                .update();
    }

    private void insertReversal(UUID id, UUID reverses) {
        h.appJdbc.sql("""
				INSERT INTO transactions (id, type, currency, reverses_transaction_id) VALUES (:id, 'TRANSFER', 'USD', :r)
				""").param("id", id).param("r", reverses).update();
    }

    private void insertPosting(UUID txn, UUID account, long amount) {
        h.appJdbc
                .sql("INSERT INTO postings (transaction_id, account_id, amount_minor) VALUES (:t, :a, :m)")
                .param("t", txn)
                .param("a", account)
                .param("m", amount)
                .update();
    }

    private boolean exists(UUID txn) {
        return h.appJdbc
                        .sql("SELECT count(*) FROM transactions WHERE id = :id")
                        .param("id", txn)
                        .query(Long.class)
                        .single()
                == 1;
    }

    /** Keeps invariant 9 true for the other tests sharing this database. */
    private void fixBalances(UUID account, long amount) {
        h.appJdbc
                .sql("UPDATE accounts SET balance_minor = balance_minor + :m WHERE id = :id")
                .param("m", amount)
                .param("id", account)
                .update();
        h.appJdbc
                .sql("UPDATE accounts SET balance_minor = balance_minor - :m WHERE id = :id")
                .param("m", amount)
                .param("id", dev.minibank.account.Account.SETTLEMENT)
                .update();
    }
}
