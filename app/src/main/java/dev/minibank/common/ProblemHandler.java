package dev.minibank.common;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/** The one place typed exceptions become RFC 9457 problem responses. */
@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        if (ex.status().is4xxClientError()) {
            log.info("request rejected: {}", ex.slug());
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        problem.setType(ex.type());
        problem.setTitle(ex.title());
        return ResponseEntity.status(ex.status()).body(problem);
    }

    /** Tomcat could not parse the query string or form body (malformed encoding, bad chunks). */
    @ExceptionHandler(InvalidParameterException.class)
    ResponseEntity<ProblemDetail> handleUnparseableParameters(InvalidParameterException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Request parameters could not be parsed");
        problem.setType(URI.create(typeFor(org.springframework.http.HttpStatus.BAD_REQUEST)));
        problem.setTitle("Bad request");
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(ProblemHandler::fieldError)
                .toList();
        return ResponseEntity.badRequest().body(validationProblem(errors));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // A @Valid body on a method that also validates a header arrives here, not as
        // MethodArgumentNotValidException; its field errors are reported per field.
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result instanceof ParameterErrors body
                        ? body.getFieldErrors().stream().map(ProblemHandler::fieldError)
                        : result.getResolvableErrors().stream()
                                .map(error -> Map.of(
                                        "field",
                                        parameterName(
                                                result.getMethodParameter().getParameterName(),
                                                result.getMethodParameter().getParameterAnnotations()),
                                        "message",
                                        String.valueOf(error.getDefaultMessage()))))
                .toList();
        return ResponseEntity.badRequest().body(validationProblem(errors));
    }

    /**
     * Every problem Spring MVC raises itself (unreadable body, wrong parameter type, missing
     * header, unsupported media type) gets a stable type URI, and a detail that names the
     * offending field or parameter without echoing the submitted value back.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (body == null) {
            body = ProblemDetail.forStatus(status);
        }
        if (body instanceof ProblemDetail problem) {
            if (problem.getType() == null
                    || "about:blank".equals(problem.getType().toString())) {
                problem.setType(URI.create(typeFor(status)));
            }
            String detail = safeDetail(ex);
            if (detail != null) {
                problem.setDetail(detail);
            }
        }
        return super.handleExceptionInternal(ex, body, headers, status, request);
    }

    static String typeFor(HttpStatusCode status) {
        String slug = switch (status.value()) {
            case 400 -> "bad-request";
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not-found";
            case 405 -> "method-not-allowed";
            case 406 -> "not-acceptable";
            case 413 -> "payload-too-large";
            case 415 -> "unsupported-media-type";
            default -> status.is5xxServerError() ? "internal-error" : "http-" + status.value();
        };
        return "https://minibank.dev/problems/" + slug;
    }

    private static String safeDetail(Exception ex) {
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            return "'%s' is not valid".formatted(snakeCase(mismatch.getName()));
        }
        if (ex instanceof TypeMismatchException mismatch) {
            return "'%s' is not valid".formatted(snakeCase(String.valueOf(mismatch.getPropertyName())));
        }
        if (ex instanceof HttpMessageNotReadableException) {
            for (Throwable t = ex; t != null; t = t.getCause()) {
                if (t instanceof UnrecognizedPropertyException unknown) {
                    return "Unknown field '%s'".formatted(unknown.getPropertyName());
                }
                if (t instanceof JacksonException json && !json.getPath().isEmpty()) {
                    String field = json.getPath().getLast().getPropertyName();
                    if (field != null) {
                        return "'%s' is not valid".formatted(field);
                    }
                }
            }
            return "Request body is not valid JSON for this operation";
        }
        return null;
    }

    private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatus.BAD_REQUEST, "One or more fields failed validation");
        problem.setType(URI.create("https://minibank.dev/problems/validation"));
        problem.setTitle("Validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static Map<String, String> fieldError(FieldError error) {
        return Map.of("field", snakeCase(error.getField()), "message", String.valueOf(error.getDefaultMessage()));
    }

    private static String parameterName(String fallback, java.lang.annotation.Annotation[] annotations) {
        for (java.lang.annotation.Annotation a : annotations) {
            if (a instanceof org.springframework.web.bind.annotation.RequestHeader header
                    && !header.value().isEmpty()) {
                return header.value();
            }
        }
        return snakeCase(String.valueOf(fallback));
    }

    static String snakeCase(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
