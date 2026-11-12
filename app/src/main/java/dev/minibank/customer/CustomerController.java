package dev.minibank.customer;

import dev.minibank.common.MaxCodePoints;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CustomerController {

    record CreateCustomerRequest(
            @NotEmpty @Email @MaxCodePoints(254) String email,

            @NotNull @Pattern(regexp = NOT_BLANK) @MaxCodePoints(200)
            String name) {}

    /** Contains at least one non-whitespace character; the same class as the spec's pattern. */
    static final String NOT_BLANK = "(?s).*[^\\x09-\\x0D\\x20].*";

    record CreatedCustomerResponse(
            UUID id,
            String email,
            String name,
            String status,
            Instant createdAt,
            CustomerService.Credentials credentials) {}

    private final CustomerService customers;

    CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @PostMapping("/v1/customers")
    ResponseEntity<CreatedCustomerResponse> create(@Valid @RequestBody CreateCustomerRequest request) {
        var created = customers.create(request.email(), request.name());
        var c = created.customer();
        return ResponseEntity.created(URI.create("/v1/customers/" + c.id()))
                .body(new CreatedCustomerResponse(
                        c.id(), c.email(), c.name(), c.status(), c.createdAt(), created.credentials()));
    }
}
