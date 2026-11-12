package dev.minibank.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One business event and its signed postings, which always sum to zero. */
public record Transaction(UUID id, String type, String status, String currency, String reference,
		List<Posting> postings, Instant postedAt) {

	public record Posting(UUID accountId, long amountMinor) {
	}

}
