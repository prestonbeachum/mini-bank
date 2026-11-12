package dev.minibank.account;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import dev.minibank.auth.Caller;
import dev.minibank.auth.Role;
import dev.minibank.common.ApiException;
import dev.minibank.common.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AccountService {

	public record Entry(long postingId, UUID transactionId, String type, long amountMinor, String reference,
			Instant postedAt) {
	}

	public record EntryPage(List<Entry> items, String nextCursor) {
	}

	private final AccountRepository accounts;

	private final JdbcClient jdbc;

	public AccountService(AccountRepository accounts, JdbcClient jdbc) {
		this.accounts = accounts;
		this.jdbc = jdbc;
	}

	public Account open(Caller caller, UUID requestedCustomerId, String kind, String currency) {
		UUID customerId;
		if (caller.is(Role.CUSTOMER)) {
			if (requestedCustomerId != null && !requestedCustomerId.equals(caller.customerId())) {
				throw ApiException.notFound("Customer");
			}
			customerId = caller.customerId();
		}
		else if (caller.is(Role.OPS)) {
			if (requestedCustomerId == null) {
				throw ApiException.badRequest("customer_id is required for ops");
			}
			customerId = requestedCustomerId;
		}
		else {
			throw ApiException.forbidden("This role may not open accounts");
		}
		Integer exists = jdbc.sql("SELECT count(*) FROM customers WHERE id = :id AND status = 'ACTIVE'")
			.param("id", customerId)
			.query(Integer.class)
			.single();
		if (exists == 0) {
			throw ApiException.notFound("Customer");
		}
		return accounts.insert(Ids.newId(), customerId, kind, currency == null ? "USD" : currency);
	}

	/** Owner or ops only. Anyone else gets 404, so account ids cannot be probed. */
	public Account get(Caller caller, UUID id) {
		Account account = accounts.find(id).orElseThrow(() -> ApiException.notFound("Account"));
		if (!canRead(caller, account)) {
			throw ApiException.notFound("Account");
		}
		return account;
	}

	/** Keyset pagination on (created_at DESC, id DESC); never OFFSET. */
	public EntryPage history(Caller caller, UUID accountId, String cursor, int limit) {
		get(caller, accountId);
		Cursor after = cursor == null ? null : Cursor.decode(cursor);
		List<Entry> rows = jdbc.sql("""
				SELECT p.id, p.transaction_id, t.type, p.amount_minor, t.reference, p.created_at
				FROM postings p JOIN transactions t ON t.id = p.transaction_id
				WHERE p.account_id = :account
				  AND (CAST(:afterTs AS timestamptz) IS NULL OR (p.created_at, p.id) < (:afterTs, :afterId))
				ORDER BY p.created_at DESC, p.id DESC
				LIMIT :fetch
				""")
			.param("account", accountId)
			.param("afterTs", after == null ? null : OffsetDateTime.ofInstant(after.createdAt(), ZoneOffset.UTC))
			.param("afterId", after == null ? 0L : after.postingId())
			.param("fetch", limit + 1)
			.query((rs, n) -> new Entry(rs.getLong("id"), rs.getObject("transaction_id", UUID.class),
					rs.getString("type"), rs.getLong("amount_minor"), rs.getString("reference"),
					rs.getObject("created_at", OffsetDateTime.class).toInstant()))
			.list();
		if (rows.size() <= limit) {
			return new EntryPage(rows, null);
		}
		List<Entry> page = rows.subList(0, limit);
		Entry last = page.getLast();
		return new EntryPage(List.copyOf(page), new Cursor(last.postedAt(), last.postingId()).encode());
	}

	private static boolean canRead(Caller caller, Account account) {
		return caller.is(Role.OPS) || caller.owns(account.customerId());
	}

	record Cursor(Instant createdAt, long postingId) {

		String encode() {
			String raw = createdAt + "|" + postingId;
			return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
		}

		static Cursor decode(String value) {
			try {
				String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
				int bar = raw.indexOf('|');
				return new Cursor(Instant.parse(raw.substring(0, bar)), Long.parseLong(raw.substring(bar + 1)));
			}
			catch (RuntimeException ex) {
				throw ApiException.badRequest("cursor is not valid");
			}
		}

	}

}
