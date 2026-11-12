package dev.minibank.ledger;

import java.util.UUID;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.minibank.account.AccountRepository;
import dev.minibank.common.Ids;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real repositories and services wired by hand against one shared Testcontainers Postgres,
 * without a Spring context. Fast enough for property tests that run thousands of operations.
 */
final class LedgerHarness {

	private static LedgerHarness instance;

	final DataSource dataSource;

	final JdbcClient jdbc;

	final TransactionTemplate tx;

	final AccountRepository accounts;

	final LedgerService ledger;

	private LedgerHarness() {
		@SuppressWarnings("resource") // lives for the JVM; Testcontainers' reaper removes it
		PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
		pg.start();
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(pg.getJdbcUrl());
		config.setUsername(pg.getUsername());
		config.setPassword(pg.getPassword());
		config.setMaximumPoolSize(24);
		dataSource = new HikariDataSource(config);
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
		jdbc = JdbcClient.create(dataSource);
		tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		accounts = new AccountRepository(jdbc);
		JsonMapper json = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
		ledger = new LedgerService(tx, accounts, new LedgerRepository(jdbc), new IdempotencyStore(jdbc), json);
	}

	static synchronized LedgerHarness get() {
		if (instance == null) {
			instance = new LedgerHarness();
		}
		return instance;
	}

	UUID newCustomer() {
		UUID id = Ids.newId();
		jdbc.sql("INSERT INTO customers (id, email, name) VALUES (:id, :email, 'Test Customer')")
			.param("id", id)
			.param("email", id + "@example.test")
			.update();
		return id;
	}

	UUID newAccount(UUID customerId) {
		return accounts.insert(Ids.newId(), customerId, "CHECKING", "USD").id();
	}

	void setOverdraftLimit(UUID accountId, long limitMinor) {
		jdbc.sql("UPDATE accounts SET overdraft_limit_minor = :l WHERE id = :id")
			.param("l", limitMinor)
			.param("id", accountId)
			.update();
	}

	long balance(UUID accountId) {
		return jdbc.sql("SELECT balance_minor FROM accounts WHERE id = :id")
			.param("id", accountId)
			.query(Long.class)
			.single();
	}

	// ---- Whole-database invariant checks (build doc section 5) ----

	/** Invariant 1: transactions whose postings do not sum to zero. */
	long unbalancedTransactions() {
		return count("SELECT count(*) FROM (SELECT transaction_id FROM postings GROUP BY 1 HAVING sum(amount_minor) <> 0) x");
	}

	/** Invariants 3 and 10: customer accounts below their floor after holds. */
	long accountsBelowFloor() {
		return count("""
				SELECT count(*) FROM accounts
				WHERE kind <> 'INTERNAL' AND balance_minor - holds_minor + overdraft_limit_minor < 0
				""");
	}

	/** Invariant 7: postings whose account currency differs from the transaction currency. */
	long currencyMismatches() {
		return count("""
				SELECT count(*) FROM postings p JOIN accounts a ON a.id = p.account_id
				JOIN transactions t ON t.id = p.transaction_id WHERE a.currency <> t.currency
				""");
	}

	/** Invariant 8: the sum of every posting in the system. */
	long globalPostingSum() {
		return count("SELECT COALESCE(sum(amount_minor), 0) FROM postings");
	}

	/** Invariant 9: accounts whose materialized balance differs from the sum of their postings. */
	long balanceDrift() {
		return count("""
				SELECT count(*) FROM accounts a
				LEFT JOIN (SELECT account_id, sum(amount_minor) s FROM postings GROUP BY 1) p ON p.account_id = a.id
				WHERE a.balance_minor <> COALESCE(p.s, 0)
				""");
	}

	/** Invariant 6: posted transactions without exactly one outbox event, and events without a transaction. */
	long outboxMismatches() {
		return count("""
				SELECT count(*) FROM
				  (SELECT t.id FROM transactions t WHERE EXISTS (SELECT 1 FROM postings p WHERE p.transaction_id = t.id)) t
				FULL JOIN (SELECT * FROM outbox_events WHERE event_type = 'transaction.posted') o
				  ON CAST(o.payload->>'id' AS uuid) = t.id
				WHERE t.id IS NULL OR o.id IS NULL
				""") + count("""
				SELECT count(*) FROM (SELECT payload->>'id' FROM outbox_events GROUP BY 1 HAVING count(*) > 1) d
				""");
	}

	private long count(String sql) {
		return jdbc.sql(sql).query(Long.class).single();
	}

}
