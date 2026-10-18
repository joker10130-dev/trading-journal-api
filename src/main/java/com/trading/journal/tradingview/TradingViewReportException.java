package com.trading.journal.tradingview;

import java.util.List;

public class TradingViewReportException extends RuntimeException {

	private final List<String> errors;
	private final int errorRows;

	public TradingViewReportException(String message) {
		this(message, List.of(message), 0);
	}

	public TradingViewReportException(String message, List<String> errors) {
		this(message, errors, errors.size());
	}

	public TradingViewReportException(String message, List<String> errors, int errorRows) {
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
