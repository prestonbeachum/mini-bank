package dev.minibank.auth;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The authenticated identity behind a request. Services authorize against this, never
 * against raw tokens, so they can be called and tested without HTTP.
 *
 * @param subject the client id; also the idempotency scope
 * @param customerId set only for the customer role
 */
public record Caller(String subject, Role role, UUID customerId) {

	public static Caller from(Jwt jwt) {
		String customer = jwt.getClaimAsString("customer_id");
		return new Caller(jwt.getSubject(), Role.fromClaim(jwt.getClaimAsString("role")),
				customer == null ? null : UUID.fromString(customer));
	}

	public static Caller customer(String subject, UUID customerId) {
		return new Caller(subject, Role.CUSTOMER, customerId);
	}

	public static Caller ops(String subject) {
		return new Caller(subject, Role.OPS, null);
	}

	public static Caller service(String subject) {
		return new Caller(subject, Role.SERVICE, null);
	}

	public boolean is(Role r) {
		return role == r;
	}

	/** True when this caller may read or debit an account owned by {@code ownerId}. */
	public boolean owns(UUID ownerId) {
		return role == Role.CUSTOMER && customerId != null && customerId.equals(ownerId);
	}

}
