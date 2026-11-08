package com.trading.journal.trade;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackageClasses = ManualTradeController.class)
public class TradeExceptionHandler {

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException exception) {
		HttpStatusCode status = exception.getStatusCode();
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getReason());
		return ResponseEntity.status(status).body(problem);
	}

}
