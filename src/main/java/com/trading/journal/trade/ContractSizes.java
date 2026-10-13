package com.trading.journal.trade;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

public final class ContractSizes {

	// Source: Eightcap contract specification, checked 13 Oct 2026.
	// Every size below is quoted in USD. Each must be confirmed against the broker's specification
	// and must not be changed without updating the design doc.
	private static final Map<String, BigDecimal> BY_SYMBOL = Map.of(
			"XAUUSD", BigDecimal.valueOf(100),
			"BTCUSD", BigDecimal.ONE);

	private ContractSizes() {
	}

	public static Optional<BigDecimal> forSymbol(String symbol) {
		return Optional.ofNullable(BY_SYMBOL.get(symbol));
	}

}
