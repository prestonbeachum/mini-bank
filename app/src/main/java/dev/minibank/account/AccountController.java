package dev.minibank.account;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import dev.minibank.auth.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccountController {

	record OpenAccountRequest(@NotNull @Pattern(regexp = "CHECKING|SAVINGS") String kind, UUID customerId,
			@Pattern(regexp = "USD") String currency) {
	}

	record AccountResponse(UUID id, UUID customerId, String kind, String currency, String status, long balanceMinor,
			long holdsMinor, long overdraftLimitMinor, long availableMinor, Instant createdAt) {

		static AccountResponse of(Account a) {
			return new AccountResponse(a.id(), a.customerId(), a.kind(), a.currency(), a.status(), a.balanceMinor(),
					a.holdsMinor(), a.overdraftLimitMinor(), a.availableMinor(), a.createdAt());
		}

	}

	private final AccountService accounts;

	AccountController(AccountService accounts) {
		this.accounts = accounts;
	}

	@PostMapping("/v1/accounts")
	ResponseEntity<AccountResponse> open(Caller caller, @Valid @RequestBody OpenAccountRequest request) {
		Account account = accounts.open(caller, request.customerId(), request.kind(), request.currency());
		return ResponseEntity.created(URI.create("/v1/accounts/" + account.id())).body(AccountResponse.of(account));
	}

	@GetMapping("/v1/accounts/{accountId}")
	AccountResponse get(Caller caller, @PathVariable UUID accountId) {
		return AccountResponse.of(accounts.get(caller, accountId));
	}

	@GetMapping("/v1/accounts/{accountId}/transactions")
	AccountService.EntryPage history(Caller caller, @PathVariable UUID accountId,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit) {
		return accounts.history(caller, accountId, cursor, limit);
	}

}
