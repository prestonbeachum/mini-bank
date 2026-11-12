package dev.minibank.common;

import java.util.UUID;

import com.github.f4b6a3.uuid.UuidCreator;

/** App-generated, time-ordered ids (UUIDv7), so primary-key inserts stay index-friendly. */
public final class Ids {

	private Ids() {
	}

	public static UUID newId() {
		return UuidCreator.getTimeOrderedEpoch();
	}

}
