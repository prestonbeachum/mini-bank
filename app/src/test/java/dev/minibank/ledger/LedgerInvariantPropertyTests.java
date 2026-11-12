package dev.minibank.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.minibank.auth.Caller;
import dev.minibank.common.ApiException;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.springframework.dao.DataAccessException;

/**
 * Random sequences of deposits, transfers and Idempotency-Key replays, checked against an
 * in-memory model and against every ledger invariant (build doc sections 5 and 12).
 */
@Tag("property")
class LedgerInvariantPropertyTests {

	private static final int ACCOUNTS = 4;

	private static final long OVERDRAFT_ON_LAST_ACCOUNT = 5_000;

	sealed interface Op permits Deposit, Transfer, Replay {
	}

	record Deposit(int account, long amount) implements Op {
	}

	/** {@code who}: 0 = owner of the source, 1 = the other customer, 2 = service role. */
	record Transfer(int source, int destination, long amount, int who) implements Op {
	}

	/** Re-sends an earlier keyed request with the same key, with the same or a different body. */
	record Replay(int earlier, boolean sameBody) implements Op {
	}

	@Provide
	Arbitrary<List<Op>> operations() {
		Arbitrary<Integer> account = Arbitraries.integers().between(0, ACCOUNTS - 1);
		Arbitrary<Long> amount = Arbitraries.longs().between(1, 20_000);
		Arbitrary<Op> deposit = Combinators.combine(account, amount).as(Deposit::new);
		Arbitrary<Op> transfer = Combinators
			.combine(account, account, amount, Arbitraries.integers().between(0, 2))
			.as(Transfer::new);
		Arbitrary<Op> replay = Combinators.combine(Arbitraries.integers().between(0, 1000), Arbitraries.of(true, false))
			.as(Replay::new);
		return Arbitraries.frequencyOf(Tuple2(3, deposit), Tuple2(6, transfer), Tuple2(2, replay))
			.list()
			.ofMinSize(1)
			.ofMaxSize(30);
	}

	private static net.jqwik.api.Tuple.Tuple2<Integer, Arbitrary<? extends Op>> Tuple2(int weight,
			Arbitrary<? extends Op> arbitrary) {
		return net.jqwik.api.Tuple.of(weight, arbitrary);
	}

	@Property(tries = 100)
	void ledgerMatchesModelAndKeepsEveryInvariant(@ForAll("operations") List<Op> ops) {
		World w = new World(LedgerHarness.get());
		for (Op op : ops) {
			w.apply(op);
		}
		w.assertMatchesModel();
		assertGlobalInvariants(w.h);
	}

	static void assertGlobalInvariants(LedgerHarness h) {
		assertThat(h.unbalancedTransactions()).as("invariant 1: unbalanced transactions").isZero();
		assertThat(h.accountsBelowFloor()).as("invariants 3/10: accounts below floor").isZero();
		assertThat(h.outboxMismatches()).as("invariant 6: outbox mismatches").isZero();
		assertThat(h.currencyMismatches()).as("invariant 7: currency mismatches").isZero();
		assertThat(h.globalPostingSum()).as("invariant 8: global posting sum").isZero();
		assertThat(h.balanceDrift()).as("invariant 9: balance drift").isZero();
		// Invariant 2: no posting can be changed or removed.
		assertThatThrownBy(() -> h.jdbc.sql("UPDATE postings SET amount_minor = amount_minor + 1").update())
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> h.jdbc.sql("DELETE FROM postings").update()).isInstanceOf(DataAccessException.class);
	}

	/** One property try: fresh customers and accounts, isolated idempotency scopes, and a model. */
	static final class World {

		final LedgerHarness h;

		final UUID[] customers = new UUID[2];

		final UUID[] accounts = new UUID[ACCOUNTS];

		final long[] model = new long[ACCOUNTS];

		final long[] limits = new long[ACCOUNTS];

		final Caller service;

		final Caller[] customerCallers = new Caller[2];

		/** Every keyed request sent so far, in order, so replays can refer back to them. */
		final List<Sent> sent = new ArrayList<>();

		/** key → transaction id, for keys that bound a successful response. */
		final Map<String, UUID> consumed = new HashMap<>();

		int successes;

		record Sent(String key, Caller caller, Op op) {
		}

		World(LedgerHarness h) {
			this.h = h;
			String run = UUID.randomUUID().toString();
			service = Caller.service("svc-" + run);
			for (int c = 0; c < 2; c++) {
				customers[c] = h.newCustomer();
				customerCallers[c] = Caller.customer("cust-" + run + "-" + c, customers[c]);
			}
			for (int a = 0; a < ACCOUNTS; a++) {
				accounts[a] = h.newAccount(customers[owner(a)]);
			}
			limits[ACCOUNTS - 1] = OVERDRAFT_ON_LAST_ACCOUNT;
			h.setOverdraftLimit(accounts[ACCOUNTS - 1], OVERDRAFT_ON_LAST_ACCOUNT);
		}

		static int owner(int account) {
			return account / 2;
		}

		void apply(Op op) {
			switch (op) {
				case Deposit d -> send("k" + sent.size(), service, d);
				case Transfer t -> send("k" + sent.size(), callerFor(t), t);
				case Replay r -> {
					if (sent.isEmpty()) {
						return;
					}
					Sent original = sent.get(r.earlier() % sent.size());
					Op body = r.sameBody() ? original.op() : withDifferentAmount(original.op());
					send(original.key(), original.caller(), body);
				}
			}
		}

		Caller callerFor(Transfer t) {
			return switch (t.who()) {
				case 0 -> customerCallers[owner(t.source())];
				case 1 -> customerCallers[1 - owner(t.source())];
				default -> service;
			};
		}

		static Op withDifferentAmount(Op op) {
			return switch (op) {
				case Deposit d -> new Deposit(d.account(), d.amount() + 1);
				case Transfer t -> new Transfer(t.source(), t.destination(), t.amount() + 1, t.who());
				case Replay r -> r;
			};
		}

		void send(String key, Caller caller, Op op) {
			boolean newKey = sent.stream().noneMatch(s -> s.key().equals(key));
			if (newKey) {
				sent.add(new Sent(key, caller, op));
			}
			Sent first = sent.stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
			String expected = expectedOutcome(key, first, op);

			LedgerService.Result result = null;
			String actual;
			try {
				result = execute(key, caller, op);
				actual = result.replayed() ? "replayed" : "posted";
			}
			catch (ApiException ex) {
				actual = ex.slug();
			}
			assertThat(actual).as("outcome of %s with key %s", op, key).isEqualTo(expected);

			if ("posted".equals(actual)) {
				successes++;
				consumed.put(key, result.transaction().id());
				applyToModel(op);
				// Later replays must match this body, not the first one sent with the key.
				sent.set(sent.indexOf(first), new Sent(key, caller, op));
			}
			if ("replayed".equals(actual)) {
				assertThat(result.transaction().id()).as("replay returns the original transaction")
					.isEqualTo(consumed.get(key));
			}
		}

		String expectedOutcome(String key, Sent first, Op op) {
			if (consumed.containsKey(key)) {
				return first.op().equals(op) ? "replayed" : "idempotency-key-reused";
			}
			return switch (op) {
				case Deposit d -> "posted";
				case Transfer t -> {
					if (t.source() == t.destination()) {
						yield "same-account";
					}
					if (t.who() == 1) {
						yield "not-found";
					}
					yield model[t.source()] + limits[t.source()] < t.amount() ? "insufficient-funds" : "posted";
				}
				case Replay r -> throw new IllegalStateException();
			};
		}

		LedgerService.Result execute(String key, Caller caller, Op op) {
			return switch (op) {
				case Deposit d -> h.ledger.deposit(caller, key,
						new LedgerService.DepositCommand(accounts[d.account()], d.amount(), "USD", null));
				case Transfer t -> h.ledger.transfer(caller, key, new LedgerService.TransferCommand(
						accounts[t.source()], accounts[t.destination()], t.amount(), "USD", "prop"));
				case Replay r -> throw new IllegalStateException();
			};
		}

		void applyToModel(Op op) {
			switch (op) {
				case Deposit d -> model[d.account()] += d.amount();
				case Transfer t -> {
					model[t.source()] -= t.amount();
					model[t.destination()] += t.amount();
				}
				case Replay r -> throw new IllegalStateException();
			}
		}

		void assertMatchesModel() {
			for (int a = 0; a < ACCOUNTS; a++) {
				assertThat(h.balance(accounts[a])).as("balance of account %d", a).isEqualTo(model[a]);
			}
			long transactions = h.jdbc
				.sql("SELECT count(DISTINCT transaction_id) FROM postings WHERE account_id IN (:ids)")
				.param("ids", List.of(accounts))
				.query(Long.class)
				.single();
			assertThat(transactions).as("invariant 5: one transaction per successful key").isEqualTo(successes);
		}

	}

}
