package dev.minibank.ledger;

import java.util.Arrays;
import java.util.Optional;

import dev.minibank.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Replay protection (invariant 5). Must be called inside the money-moving DB transaction: the
 * key row commits with the ledger write or not at all, so a failed request never consumes a key.
 */
@Repository
public class IdempotencyStore {

	private final JdbcClient jdbc;

	public IdempotencyStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Claims the key, or returns the stored response body if this exact request already succeeded.
	 * A concurrent duplicate blocks on the primary key until the first request commits or rolls
	 * back, then sees its outcome. Keys older than 24 hours are treated as free.
	 */
	public Optional<String> claim(String scope, String key, byte[] requestHash) {
		int claimed = jdbc.sql("""
				INSERT INTO idempotency_keys (scope, key, request_hash) VALUES (:scope, :key, :hash)
				ON CONFLICT (scope, key) DO UPDATE
				  SET request_hash = EXCLUDED.request_hash, response_status = NULL, response_body = NULL,
				      created_at = now()
				  WHERE idempotency_keys.created_at < now() - interval '24 hours'
				""")
			.param("scope", scope)
			.param("key", key)
			.param("hash", requestHash)
			.update();
		if (claimed == 1) {
			return Optional.empty();
		}
		record Stored(byte[] hash, String body) {
		}
		Stored stored = jdbc
			.sql("SELECT request_hash, response_body::text AS body FROM idempotency_keys WHERE scope = :scope AND key = :key")
			.param("scope", scope)
			.param("key", key)
			.query((rs, n) -> new Stored(rs.getBytes("request_hash"), rs.getString("body")))
			.single();
		if (!Arrays.equals(stored.hash(), requestHash)) {
			throw ApiException.idempotencyKeyReused();
		}
		if (stored.body() == null) {
			throw new ApiException(HttpStatus.CONFLICT, "idempotency-in-progress", "Request in progress",
					"A request with this Idempotency-Key has not finished");
		}
		return Optional.of(stored.body());
	}

	public void complete(String scope, String key, int status, String responseJson) {
		jdbc.sql("""
				UPDATE idempotency_keys SET response_status = :status, response_body = CAST(:body AS jsonb)
				WHERE scope = :scope AND key = :key
				""")
			.param("status", status)
			.param("body", responseJson)
			.param("scope", scope)
			.param("key", key)
			.update();
	}

}
