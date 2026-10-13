package com.trading.journal.eightcap;

import java.util.List;

public class EightcapReportException extends RuntimeException {

	private final List<String> errors;

	public EightcapReportException(String message) {
		this(message, List.of(message));
	}

	public EightcapReportException(String message, List<String> errors) {
		super(message);
		this.errors = List.copyOf(errors);
	}

	public List<String> errors() {
		return errors;
	}

}
