package com.trading.journal.tradingview;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class TradingViewReportExceptionHandler {

	@ExceptionHandler(TradingViewReportException.class)
	public ProblemDetail handleReportError(TradingViewReportException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST,
				exception.getMessage());
		problem.setTitle("Invalid TradingView report");
		problem.setProperty("errors", exception.errors());
		problem.setProperty("errorRows", exception.errorRows());
		return problem;
	}

}
