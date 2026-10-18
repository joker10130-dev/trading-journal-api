package com.trading.journal.tradingview;

import java.util.List;

public class TradingViewReportException extends RuntimeException {

	private final List<String> errors;

	public TradingViewReportException(String message) {
		this(message, List.of(message));
	}

	public TradingViewReportException(String message, List<String> errors) {
		super(message);
		this.errors = List.copyOf(errors);
	}

	public List<String> errors() {
		return errors;
	}

}
