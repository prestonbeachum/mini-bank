package dev.minibank.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApiClientRepository {

	public record ApiClient(String clientId, String secretHash, Role role, UUID customerId) {
	}

	private final JdbcClient jdbc;

	public ApiClientRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<ApiClient> findActive(String clientId) {
		return jdbc.sql("""
				SELECT client_id, secret_hash, role, customer_id
				FROM api_clients WHERE client_id = :id AND disabled_at IS NULL
				""")
			.param("id", clientId)
			.query((rs, n) -> new ApiClient(rs.getString("client_id"), rs.getString("secret_hash"),
					Role.fromClaim(rs.getString("role")), rs.getObject("customer_id", UUID.class)))
			.optional();
	}

	public void insert(String clientId, String secretHash, Role role, UUID customerId) {
		jdbc.sql("""
				INSERT INTO api_clients (client_id, secret_hash, role, customer_id)
				VALUES (:id, :hash, :role, :customer)
				""")
			.param("id", clientId)
			.param("hash", secretHash)
			.param("role", role.claim())
			.param("customer", customerId)
			.update();
	}

	/** For configuration-provisioned ops and service clients: create, or rotate the secret. */
	public void upsertSystemClient(String clientId, String secretHash, Role role) {
		jdbc.sql("""
				INSERT INTO api_clients (client_id, secret_hash, role)
				VALUES (:id, :hash, :role)
				ON CONFLICT (client_id) DO UPDATE SET secret_hash = EXCLUDED.secret_hash, role = EXCLUDED.role,
				  disabled_at = NULL
				""")
			.param("id", clientId)
			.param("hash", secretHash)
			.param("role", role.claim())
			.update();
	}

}
