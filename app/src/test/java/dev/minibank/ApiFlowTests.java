package dev.minibank;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The Core-tier API end to end over HTTP, against the contract in api/openapi.yaml. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "minibank.auth.bootstrap-clients[0].client-id=cli_ops_test",
            "minibank.auth.bootstrap-clients[0].secret=ops-test-secret",
            "minibank.auth.bootstrap-clients[0].role=ops",
            "minibank.auth.bootstrap-clients[1].client-id=cli_service_test",
            "minibank.auth.bootstrap-clients[1].secret=service-test-secret",
            "minibank.auth.bootstrap-clients[1].role=service"
        })
class ApiFlowTests {

    private static final JsonMapper JSON = new JsonMapper();

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    String ops;

    String service;

    @BeforeEach
    void tokens() throws Exception {
        ops = token("cli_ops_test", "ops-test-secret");
        service = token("cli_service_test", "service-test-secret");
    }

    @Test
    void customerOpensAccountsReceivesDepositTransfersAndReadsHistory() throws Exception {
        Customer ada = newCustomer();
        Customer bob = newCustomer();
        String checking = openAccount(ada.token());
        String bobs = openAccount(bob.token());

        var deposit = send(
                "POST",
                "/v1/deposits",
                service,
                UUID.randomUUID().toString(),
                "{\"account_id\":\"%s\",\"amount_minor\":10000,\"currency\":\"USD\",\"reference\":\"payroll\"}"
                        .formatted(checking));
        assertThat(deposit.statusCode()).isEqualTo(201);
        JsonNode depositBody = JSON.readTree(deposit.body());
        assertThat(depositBody.get("type").asString()).isEqualTo("DEPOSIT");
        assertThat(depositBody.get("postings")).hasSize(2);
        assertThat(depositBody.has("posted_at")).as("snake_case on the wire").isTrue();

        String key = UUID.randomUUID().toString();
        String transfer =
                "{\"source_account_id\":\"%s\",\"destination_account_id\":\"%s\",\"amount_minor\":2500,\"currency\":\"USD\"}"
                        .formatted(checking, bobs);
        var first = send("POST", "/v1/transfers", ada.token(), key, transfer);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.headers().firstValue("Idempotent-Replayed")).isEmpty();

        var replay = send("POST", "/v1/transfers", ada.token(), key, transfer);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(JSON.readTree(replay.body()).get("id"))
                .isEqualTo(JSON.readTree(first.body()).get("id"));

        var reused = send("POST", "/v1/transfers", ada.token(), key, transfer.replace("2500", "2600"));
        assertProblem(reused, 422, "idempotency-key-reused");

        JsonNode account = JSON.readTree(
                send("GET", "/v1/accounts/" + checking, ada.token(), null, null).body());
        assertThat(account.get("balance_minor").asLong()).isEqualTo(7500);
        assertThat(account.get("available_minor").asLong()).isEqualTo(7500);

        JsonNode page1 =
                JSON.readTree(send("GET", "/v1/accounts/" + checking + "/transactions?limit=1", ada.token(), null, null)
                        .body());
        assertThat(page1.get("items")).hasSize(1);
        assertThat(page1.get("items").get(0).get("amount_minor").asLong())
                .as("newest first")
                .isEqualTo(-2500);
        String cursor = page1.get("next_cursor").asString();
        JsonNode page2 = JSON.readTree(send(
                        "GET",
                        "/v1/accounts/" + checking + "/transactions?limit=1&cursor=" + cursor,
                        ada.token(),
                        null,
                        null)
                .body());
        assertThat(page2.get("items").get(0).get("amount_minor").asLong()).isEqualTo(10000);
        assertThat(page2.get("next_cursor").isNull()).isTrue();
    }

    @Test
    void anotherCustomersAccountIsNotFound() throws Exception {
        Customer ada = newCustomer();
        Customer eve = newCustomer();
        String adas = openAccount(ada.token());
        String eves = openAccount(eve.token());

        assertProblem(send("GET", "/v1/accounts/" + adas, eve.token(), null, null), 404, "not-found");
        assertProblem(
                send(
                        "POST",
                        "/v1/transfers",
                        eve.token(),
                        UUID.randomUUID().toString(),
                        "{\"source_account_id\":\"%s\",\"destination_account_id\":\"%s\",\"amount_minor\":1,\"currency\":\"USD\"}"
                                .formatted(adas, eves)),
                404,
                "not-found");
    }

    @Test
    void businessRuleRejections() throws Exception {
        Customer ada = newCustomer();
        String a = openAccount(ada.token());
        String b = openAccount(ada.token());

        assertProblem(transfer(ada.token(), a, b, 1), 422, "insufficient-funds");
        assertProblem(transfer(ada.token(), a, a, 1), 422, "same-account");
    }

    @Test
    void rolesAreEnforcedPerRoute() throws Exception {
        Customer ada = newCustomer();
        String a = openAccount(ada.token());

        assertProblem(
                send(
                        "POST",
                        "/v1/deposits",
                        ada.token(),
                        "k1",
                        "{\"account_id\":\"%s\",\"amount_minor\":1,\"currency\":\"USD\"}".formatted(a)),
                403,
                "forbidden");
        assertProblem(
                send("POST", "/v1/customers", service, null, "{\"email\":\"x@example.test\",\"name\":\"X\"}"),
                403,
                "forbidden");
        assertProblem(send("GET", "/v1/accounts/" + a, null, null, null), 401, "unauthorized");
        assertProblem(send("GET", "/v1/accounts/" + a, "not-a-jwt", null, null), 401, "unauthorized");
        assertProblem(
                post("/v1/auth/token", null, "{\"client_id\":\"cli_ops_test\",\"client_secret\":\"wrong\"}"),
                401,
                "invalid-credentials");
    }

    @Test
    void malformedRequestsAreBadRequests() throws Exception {
        Customer ada = newCustomer();
        String a = openAccount(ada.token());
        String body = "{\"account_id\":\"%s\",\"amount_minor\":%s,\"currency\":\"%s\"}";

        assertProblem(send("POST", "/v1/deposits", service, null, body.formatted(a, "100", "USD")), 400, null);
        var invalid = send("POST", "/v1/deposits", service, "k", body.formatted(a, "0", "EUR"));
        assertProblem(invalid, 400, "validation");
        assertThat(JSON.readTree(invalid.body()).get("errors").toString())
                .contains("amount_minor")
                .contains("currency");
        assertProblem(send("POST", "/v1/deposits", service, "k", body.formatted(a, "10.5", "USD")), 400, null);
        assertProblem(
                send("GET", "/v1/accounts/" + a + "/transactions?cursor=garbage", ada.token(), null, null),
                400,
                "bad-request");
    }

    @Test
    void opsOpensAccountsOnACustomersBehalfOnlyThroughTheCustomerRoute() throws Exception {
        Customer ada = newCustomer();
        var opened = send("POST", "/v1/customers/" + ada.id() + "/accounts", ops, null, "{\"kind\":\"SAVINGS\"}");
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        assertThat(JSON.readTree(opened.body()).get("customer_id").asString()).isEqualTo(ada.id());

        assertProblem(send("POST", "/v1/accounts", ops, null, "{\"kind\":\"CHECKING\"}"), 403, "forbidden");
        assertProblem(
                send("POST", "/v1/customers/" + ada.id() + "/accounts", ada.token(), null, "{\"kind\":\"CHECKING\"}"),
                403,
                "forbidden");
        assertProblem(
                send("POST", "/v1/customers/" + UUID.randomUUID() + "/accounts", ops, null, "{\"kind\":\"CHECKING\"}"),
                404,
                "not-found");
    }

    @Test
    void contractEdgesFoundByFuzzing() throws Exception {
        Customer ada = newCustomer();

        var unknownField =
                send("POST", "/v1/accounts", ada.token(), null, "{\"kind\":\"CHECKING\",\"customer_id\":\"\"}");
        assertProblem(unknownField, 400, "bad-request");
        assertThat(JSON.readTree(unknownField.body()).get("detail").asString())
                .isEqualTo("Unknown field 'customer_id'");

        var badId = send("GET", "/v1/accounts/not-a-uuid", ada.token(), null, null);
        assertProblem(badId, 400, "bad-request");
        assertThat(JSON.readTree(badId.body()).get("detail").asString())
                .as("names the parameter, never echoes the value")
                .isEqualTo("'account_id' is not valid");

        var wrongMethod = send("PUT", "/v1/deposits", service, null, "{}");
        assertProblem(wrongMethod, 405, "method-not-allowed");
        assertThat(wrongMethod.headers().firstValue("Allow")).contains("OPTIONS, POST");
        assertProblem(send("QUERY", "/health", null, null, null), 405, "method-not-allowed");
        assertProblem(
                send("DELETE", "/v1/accounts/" + UUID.randomUUID(), ada.token(), null, null),
                405,
                "method-not-allowed");

        assertProblem(send("GET", "/v1/nope", ada.token(), null, null), 403, "forbidden");

        // JSON Schema treats 233.0 as the integer 233; a fractional amount is never truncated.
        String account = openAccount(ada.token());
        var whole = send(
                "POST",
                "/v1/deposits",
                service,
                UUID.randomUUID().toString(),
                "{\"account_id\":\"%s\",\"amount_minor\":233.0,\"currency\":\"USD\"}".formatted(account));
        assertThat(whole.statusCode()).as(whole.body()).isEqualTo(201);
        var fractional = send(
                "POST",
                "/v1/deposits",
                service,
                UUID.randomUUID().toString(),
                "{\"account_id\":\"%s\",\"amount_minor\":233.5,\"currency\":\"USD\"}".formatted(account));
        assertProblem(fractional, 400, "bad-request");
        assertThat(JSON.readTree(fractional.body()).get("detail").asString()).isEqualTo("'amount_minor' is not valid");

        // A bogus multipart Content-Type used to reach the multipart resolver and fail with a 500.
        HttpResponse<String> multipart = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health"))
                        .header("Content-Type", "multipart/form-data")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(multipart.statusCode()).isEqualTo(200);

        // Optional fields: null means the same as omitted (spec: type [string, null]).
        var nullCurrency = send("POST", "/v1/accounts", ada.token(), null, "{\"kind\":\"CHECKING\",\"currency\":null}");
        assertThat(nullCurrency.statusCode()).isEqualTo(201);
        assertThat(JSON.readTree(nullCurrency.body()).get("currency").asString())
                .isEqualTo("USD");
        // Required strings may not be empty.
        assertProblem(send("POST", "/v1/customers", ops, null, "{\"email\":\"\",\"name\":\"Ada\"}"), 400, "validation");

        // Credentials are ASCII; length is never counted in UTF-16 units.
        assertProblem(
                post("/v1/auth/token", null, "{\"client_id\":\"𝐓\",\"client_secret\":\"x\"}"), 400, "validation");
    }

    @Test
    void jwksServesThePublicKeyOnly() throws Exception {
        JsonNode jwks = JSON.readTree(
                send("GET", "/.well-known/jwks.json", null, null, null).body());
        JsonNode key = jwks.get("keys").get(0);
        assertThat(key.get("kty").asString()).isEqualTo("RSA");
        assertThat(key.has("d")).as("private exponent must never be published").isFalse();
    }

    // ---- helpers ----

    record Customer(String id, String token) {}

    private Customer newCustomer() throws Exception {
        var response = send(
                "POST",
                "/v1/customers",
                ops,
                null,
                "{\"email\":\"%s@example.test\",\"name\":\"Test Customer\"}".formatted(UUID.randomUUID()));
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = JSON.readTree(response.body());
        JsonNode creds = body.get("credentials");
        return new Customer(
                body.get("id").asString(),
                token(
                        creds.get("client_id").asString(),
                        creds.get("client_secret").asString()));
    }

    private String openAccount(String token) throws Exception {
        var response = send("POST", "/v1/accounts", token, null, "{\"kind\":\"CHECKING\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        return JSON.readTree(response.body()).get("id").asString();
    }

    private HttpResponse<String> transfer(String token, String from, String to, long amount) throws Exception {
        return send(
                "POST",
                "/v1/transfers",
                token,
                UUID.randomUUID().toString(),
                "{\"source_account_id\":\"%s\",\"destination_account_id\":\"%s\",\"amount_minor\":%d,\"currency\":\"USD\"}"
                        .formatted(from, to, amount));
    }

    private String token(String clientId, String secret) throws Exception {
        var response = post(
                "/v1/auth/token", null, "{\"client_id\":\"%s\",\"client_secret\":\"%s\"}".formatted(clientId, secret));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("access_token").asString();
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return send("POST", path, token, null, body);
    }

    private HttpResponse<String> send(String method, String path, String token, String idempotencyKey, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String slug) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(ct -> assertThat(ct).startsWith("application/problem+json"));
        if (slug != null) {
            assertThat(JSON.readTree(response.body()).get("type").asString()).endsWith("/" + slug);
        }
    }
}
