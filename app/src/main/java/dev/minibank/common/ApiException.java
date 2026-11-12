package dev.minibank.common;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * A typed failure that maps to one RFC 9457 problem response. Business-rule rejections are
 * normal outcomes: they are logged and counted, never sent to Sentry (build doc section 13).
 */
public class ApiException extends RuntimeException {

    private static final String TYPE_BASE = "https://minibank.dev/problems/";

    private final HttpStatus status;

    private final String slug;

    private final String title;

    public ApiException(HttpStatus status, String slug, String title, String detail) {
        super(detail);
        this.status = status;
        this.slug = slug;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String slug() {
        return slug;
    }

    public URI type() {
        return URI.create(TYPE_BASE + slug);
    }

    public String title() {
        return title;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", what + " not found");
    }

    public static ApiException insufficientFunds() {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "insufficient-funds",
                "Insufficient funds",
                "The source account's available funds do not cover this amount");
    }

    public static ApiException currencyMismatch() {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "currency-mismatch",
                "Currency mismatch",
                "The request currency does not match the account currency");
    }

    public static ApiException sameAccount() {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "same-account",
                "Same account",
                "Source and destination must be different accounts");
    }

    public static ApiException idempotencyKeyReused() {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "idempotency-key-reused",
                "Idempotency-Key reused",
                "This Idempotency-Key was already used with a different request body");
    }

    public static ApiException accountNotActive() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "account-not-active",
                "Account not active",
                "An account in this request is frozen or closed");
    }

    public static ApiException duplicateEmail() {
        return new ApiException(
                HttpStatus.CONFLICT, "duplicate-email", "Duplicate email", "A customer with this email already exists");
    }

    public static ApiException invalidCredentials() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED,
                "invalid-credentials",
                "Invalid credentials",
                "Unknown client or wrong secret");
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad-request", "Bad request", detail);
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", detail);
    }
}
