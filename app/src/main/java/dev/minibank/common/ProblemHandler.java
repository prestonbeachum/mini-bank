package dev.minibank.common;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

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

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<Map<String, String>> errors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(ProblemHandler::fieldError)
			.toList();
		return ResponseEntity.badRequest().body(validationProblem(errors));
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		// A @Valid body on a method that also validates a header arrives here, not as
		// MethodArgumentNotValidException; its field errors are reported per field.
		List<Map<String, String>> errors = ex.getParameterValidationResults()
			.stream()
			.flatMap(result -> result instanceof ParameterErrors body
					? body.getFieldErrors().stream().map(ProblemHandler::fieldError)
					: result.getResolvableErrors()
						.stream()
						.map(error -> Map.of("field",
								parameterName(result.getMethodParameter().getParameterName(),
										result.getMethodParameter().getParameterAnnotations()),
								"message", String.valueOf(error.getDefaultMessage()))))
			.toList();
		return ResponseEntity.badRequest().body(validationProblem(errors));
	}

	private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.BAD_REQUEST,
				"One or more fields failed validation");
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
			if (a instanceof org.springframework.web.bind.annotation.RequestHeader header && !header.value().isEmpty()) {
				return header.value();
			}
		}
		return snakeCase(String.valueOf(fallback));
	}

	static String snakeCase(String camel) {
		return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
	}

}
