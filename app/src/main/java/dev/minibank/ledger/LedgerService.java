package dev.minibank.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.minibank.account.Account;
import dev.minibank.account.AccountRepository;
import dev.minibank.auth.Caller;
import dev.minibank.auth.Role;
import dev.minibank.common.ApiException;
import dev.minibank.common.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every money move: one short READ COMMITTED transaction, account rows locked in ascending id
 * order, no network calls inside. The database's constraints and deferred trigger are the
 * backstop if a check here is ever wrong (build doc sections 5 to 7).
 */
@Service
public class LedgerService {

	public record DepositCommand(UUID accountId, long amountMinor, String currency, String reference) {
	}

	public record TransferCommand(UUID sourceAccountId, UUID destinationAccountId, long amountMinor, String currency,
			String reference) {
	}

	/** @param replayed true when this is the stored response for a repeated Idempotency-Key */
	public record Result(Transaction transaction, boolean replayed) {
	}

	static final String EVENT_POSTED = "transaction.posted";

	private final TransactionTemplate tx;

	private final AccountRepository accounts;

	private final LedgerRepository ledger;

	private final IdempotencyStore idempotency;

	private final JsonMapper json;

	public LedgerService(TransactionTemplate tx, AccountRepository accounts, LedgerRepository ledger,
			IdempotencyStore idempotency, JsonMapper json) {
		this.tx = tx;
		this.accounts = accounts;
		this.ledger = ledger;
		this.idempotency = idempotency;
		this.json = json;
	}

	/** Credits an account from the mock external source, debiting the settlement account. */
	public Result deposit(Caller caller, String idempotencyKey, DepositCommand cmd) {
		requireRole(caller, Role.SERVICE);
		byte[] hash = requestHash("DEPOSIT", cmd.accountId(), cmd.amountMinor(), cmd.currency(), cmd.reference());
		return tx.execute(status -> {
			var replay = idempotency.claim(caller.subject(), idempotencyKey, hash);
			if (replay.isPresent()) {
				return new Result(json.readValue(replay.get(), Transaction.class), true);
			}
			Map<UUID, Account> locked = lock(Account.SETTLEMENT, cmd.accountId());
			Account target = locked.get(cmd.accountId());
			if (target == null || target.isInternal()) {
				throw ApiException.notFound("Account");
			}
			checkActive(target);
			checkCurrency(cmd.currency(), target, locked.get(Account.SETTLEMENT));
			Transaction t = post("DEPOSIT", cmd.currency(), cmd.reference(),
					List.of(new Transaction.Posting(Account.SETTLEMENT, -cmd.amountMinor()),
							new Transaction.Posting(target.id(), cmd.amountMinor())));
			idempotency.complete(caller.subject(), idempotencyKey, 201, json.writeValueAsString(t));
			return new Result(t, false);
		});
	}

	/**
	 * Customers may debit only accounts they own (anything else is 404); the service role may
	 * move money between any two accounts.
	 */
	public Result transfer(Caller caller, String idempotencyKey, TransferCommand cmd) {
		requireRole(caller, Role.CUSTOMER, Role.SERVICE);
		if (cmd.sourceAccountId().equals(cmd.destinationAccountId())) {
			throw ApiException.sameAccount();
		}
		byte[] hash = requestHash("TRANSFER", cmd.sourceAccountId(), cmd.destinationAccountId(), cmd.amountMinor(),
				cmd.currency(), cmd.reference());
		return tx.execute(status -> {
			var replay = idempotency.claim(caller.subject(), idempotencyKey, hash);
			if (replay.isPresent()) {
				return new Result(json.readValue(replay.get(), Transaction.class), true);
			}
			Map<UUID, Account> locked = lock(cmd.sourceAccountId(), cmd.destinationAccountId());
			Account source = locked.get(cmd.sourceAccountId());
			Account destination = locked.get(cmd.destinationAccountId());
			if (source == null || (caller.is(Role.CUSTOMER) && !caller.owns(source.customerId()))) {
				throw ApiException.notFound("Account");
			}
			if (destination == null) {
				throw ApiException.notFound("Account");
			}
			checkActive(source);
			checkActive(destination);
			checkCurrency(cmd.currency(), source, destination);
			if (!source.isInternal() && source.availableMinor() < cmd.amountMinor()) {
				throw ApiException.insufficientFunds();
			}
			Transaction t = post("TRANSFER", cmd.currency(), cmd.reference(),
					List.of(new Transaction.Posting(source.id(), -cmd.amountMinor()),
							new Transaction.Posting(destination.id(), cmd.amountMinor())));
			idempotency.complete(caller.subject(), idempotencyKey, 201, json.writeValueAsString(t));
			return new Result(t, false);
		});
	}

	private Transaction post(String type, String currency, String reference, List<Transaction.Posting> postings) {
		UUID id = Ids.newId();
		Instant postedAt = ledger.insertTransaction(id, type, currency, reference);
		for (Transaction.Posting p : postings) {
			ledger.insertPosting(id, p.accountId(), p.amountMinor());
			accounts.applyDelta(p.accountId(), p.amountMinor());
		}
		Transaction t = new Transaction(id, type, "POSTED", currency, reference, postings, postedAt);
		ledger.insertOutboxEvent(Ids.newId(), EVENT_POSTED, json.writeValueAsString(t));
		return t;
	}

	private Map<UUID, Account> lock(UUID... ids) {
		return accounts.lockInIdOrder(List.of(ids))
			.stream()
			.collect(Collectors.toMap(Account::id, Function.identity()));
	}

	private static void checkActive(Account account) {
		if (!account.isActive()) {
			throw ApiException.accountNotActive();
		}
	}

	private static void checkCurrency(String currency, Account... involved) {
		for (Account a : involved) {
			if (!a.currency().equals(currency)) {
				throw ApiException.currencyMismatch();
			}
		}
	}

	private static void requireRole(Caller caller, Role... allowed) {
		for (Role r : allowed) {
			if (caller.is(r)) {
				return;
			}
		}
		throw ApiException.forbidden("This role may not move money this way");
	}

	/** Canonical hash of the fields that define the request, so key reuse with a different body is caught. */
	static byte[] requestHash(Object... fields) {
		String canonical = java.util.Arrays.stream(fields)
			.map(f -> f == null ? "∅" : f.toString())
			.collect(Collectors.joining("\u001f"));
		try {
			return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
