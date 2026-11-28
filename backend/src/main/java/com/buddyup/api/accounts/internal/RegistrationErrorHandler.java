package com.buddyup.api.accounts.internal;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice(assignableTypes = { RegistrationController.class, AccountAccessController.class })
class RegistrationErrorHandler {

	@ExceptionHandler(RegistrationFailure.class)
	ResponseEntity<ProblemDetail> handleFailure(RegistrationFailure failure, HttpServletRequest request) {
		if (failure.status == HttpStatus.SERVICE_UNAVAILABLE
				&& !request.getServletPath().startsWith("/v1/auth/registration")) {
			failure = RegistrationFailure.accountAccessUnavailable();
		}
		var response = ResponseEntity.status(failure.status).cacheControl(CacheControl.noStore());
		if (failure.retryAfter > 0) {
			response.header(HttpHeaders.RETRY_AFTER, Long.toString(failure.retryAfter));
		}
		return response.body(problem(failure.status, failure.code));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception) {
		List<FieldProblem> fields = exception.getBindingResult().getFieldErrors().stream()
				.map(error -> new FieldProblem(error.getField(), "INVALID_FIELD"))
				.distinct().toList();
		ProblemDetail response = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
		response.setProperty("fieldErrors", fields);
		return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(response);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ProblemDetail> handleUnreadableRequest() {
		return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
				.body(problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED"));
	}

	private ProblemDetail problem(HttpStatus status, String code) {
		var problem = ProblemDetail.forStatus(status);
		problem.setProperty("code", code);
		return problem;
	}

	record FieldProblem(String field, String code) {}
}
