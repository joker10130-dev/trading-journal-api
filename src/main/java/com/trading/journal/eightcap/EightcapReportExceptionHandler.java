package com.trading.journal.eightcap;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class EightcapReportExceptionHandler {

	@ExceptionHandler(EightcapReportException.class)
	public ProblemDetail handleReportError(EightcapReportException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST,
				exception.getMessage());
		problem.setTitle("Invalid Eightcap report");
		problem.setProperty("errors", exception.errors());
		return problem;
	}

}
