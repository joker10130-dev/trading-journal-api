package com.trading.journal.eightcap;

import java.util.List;

public class EightcapReportException extends RuntimeException {

	private final List<String> errors;
	private final int errorRows;

	public EightcapReportException(String message) {
		this(message, List.of(message), 0);
	}

	public EightcapReportException(String message, List<String> errors) {
		this(message, errors, errors.size());
	}

	public EightcapReportException(String message, List<String> errors, int errorRows) {
		super(message);
		this.errors = List.copyOf(errors);
		this.errorRows = errorRows;
	}

	public List<String> errors() {
		return errors;
	}

	public int errorRows() {
		return errorRows;
	}

}
