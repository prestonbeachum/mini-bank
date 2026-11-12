package dev.minibank.ledger;

import dev.minibank.auth.Caller;
import dev.minibank.common.MaxCodePoints;
import dev.minibank.common.MinorUnits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.annotation.JsonDeserialize;

@RestController
class MoneyController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    static final String REPLAYED = "Idempotent-Replayed";

    record DepositRequest(
            @NotNull UUID accountId,

            @NotNull @Min(1) @Max(100_000_000) @JsonDeserialize(using = MinorUnits.class)
            Long amountMinor,

            @NotNull @Pattern(regexp = "USD") String currency,

            @MaxCodePoints(140) @Pattern(regexp = "[^\\x00]*")
            String reference) {}

    record TransferRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID destinationAccountId,

            @NotNull @Min(1) @Max(100_000_000) @JsonDeserialize(using = MinorUnits.class)
            Long amountMinor,

            @NotNull @Pattern(regexp = "USD") String currency,

            @MaxCodePoints(140) @Pattern(regexp = "[^\\x00]*")
            String reference) {}

    private final LedgerService ledger;

    MoneyController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/v1/deposits")
    ResponseEntity<Transaction> deposit(
            Caller caller,
            @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 1, max = 255) @Pattern(regexp = "[\\x21-\\x7E]+") String key,
            @Valid @RequestBody DepositRequest request) {
        return respond(ledger.deposit(
                caller,
                key,
                new LedgerService.DepositCommand(
                        request.accountId(), request.amountMinor(), request.currency(), request.reference())));
    }

    @PostMapping("/v1/transfers")
    ResponseEntity<Transaction> transfer(
            Caller caller,
            @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 1, max = 255) @Pattern(regexp = "[\\x21-\\x7E]+") String key,
            @Valid @RequestBody TransferRequest request) {
        return respond(ledger.transfer(
                caller,
                key,
                new LedgerService.TransferCommand(
                        request.sourceAccountId(),
                        request.destinationAccountId(),
                        request.amountMinor(),
                        request.currency(),
                        request.reference())));
    }

    private static ResponseEntity<Transaction> respond(LedgerService.Result result) {
        var builder = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            builder.header(REPLAYED, "true");
        }
        return builder.body(result.transaction());
    }
}
