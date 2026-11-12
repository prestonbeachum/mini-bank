package dev.minibank.account;

import java.time.Instant;
import java.util.UUID;

public record Account(UUID id, UUID customerId, String kind, String currency, String status, long balanceMinor,
		long holdsMinor, long overdraftLimitMinor, Instant createdAt) {

	public static final UUID SETTLEMENT = UUID.fromString("00000000-0000-7000-8000-000000000001");

	public static final UUID FEES = UUID.fromString("00000000-0000-7000-8000-000000000002");

	/** balance − holds + overdraft limit. Meaningless for internal accounts, which have no floor. */
	public long availableMinor() {
		return balanceMinor - holdsMinor + overdraftLimitMinor;
	}

	public boolean isInternal() {
		return "INTERNAL".equals(kind);
	}

	public boolean isActive() {
		return "ACTIVE".equals(status);
	}

}
