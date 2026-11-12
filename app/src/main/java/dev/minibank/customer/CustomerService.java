package dev.minibank.customer;

import dev.minibank.auth.ApiClientRepository;
import dev.minibank.auth.Role;
import dev.minibank.common.ApiException;
import dev.minibank.common.Ids;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CustomerService {

    public record Customer(UUID id, String email, String name, String status, Instant createdAt) {}

    public record Credentials(String clientId, String clientSecret) {}

    public record CreatedCustomer(Customer customer, Credentials credentials) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;

    private final TransactionTemplate tx;

    private final ApiClientRepository clients;

    private final PasswordEncoder passwords;

    public CustomerService(
            JdbcClient jdbc, TransactionTemplate tx, ApiClientRepository clients, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clients = clients;
        this.passwords = passwords;
    }

    /** Creates the customer and its API credentials atomically. The secret is returned once. */
    public CreatedCustomer create(String email, String name) {
        UUID id = Ids.newId();
        String clientId = "cli_" + token(9);
        String secret = token(24);
        String hash = passwords.encode(secret);
        try {
            Customer customer = tx.execute(status -> {
                Customer c = jdbc.sql("""
						INSERT INTO customers (id, email, name) VALUES (:id, :email, :name)
						RETURNING id, email, name, status, created_at
						""")
                        .param("id", id)
                        .param("email", email)
                        .param("name", name)
                        .query((rs, n) -> new Customer(
                                rs.getObject("id", UUID.class),
                                rs.getString("email"),
                                rs.getString("name"),
                                rs.getString("status"),
                                rs.getObject("created_at", java.time.OffsetDateTime.class)
                                        .withOffsetSameInstant(ZoneOffset.UTC)
                                        .toInstant()))
                        .single();
                clients.insert(clientId, hash, Role.CUSTOMER, id);
                return c;
            });
            return new CreatedCustomer(customer, new Credentials(clientId, secret));
        } catch (DuplicateKeyException ex) {
            throw ApiException.duplicateEmail();
        }
    }

    private static String token(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
