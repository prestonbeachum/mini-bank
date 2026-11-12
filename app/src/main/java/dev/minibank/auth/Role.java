package dev.minibank.auth;

public enum Role {

	CUSTOMER, OPS, SERVICE;

	/** The value stored in the database and in the JWT {@code role} claim. */
	public String claim() {
		return name().toLowerCase();
	}

	public static Role fromClaim(String claim) {
		return Role.valueOf(claim.toUpperCase());
	}

}
