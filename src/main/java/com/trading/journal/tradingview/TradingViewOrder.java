package com.trading.journal.tradingview;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record TradingViewOrder(
		int lineNumber,
		String symbol,
		String side,
		String type,
		BigDecimal quantity,
		BigDecimal filledQuantity,
		BigDecimal limitPrice,
		BigDecimal stopPrice,
		BigDecimal averageFillPrice,
		String status,
		LocalDateTime updateTime,
		String positionId,
		BigDecimal commission,
		BigDecimal closedPnl,
		BigDecimal netClosedPnl,
		String orderId,
		Map<String, String> rawRow) {

	private static final List<String> COLUMNS = List.of(
			"Symbol", "Side", "Type", "Qty", "Filled Qty", "Limit Price", "Stop Price",
			"Avg Fill Price", "Status", "Update Time", "Position ID", "Commission",
			"Closed P&L", "Net Closed P&L", "Order ID");
	private static final Pattern POSITION_ID = Pattern.compile("^([^:]+):(\\d+)$");

	public TradingViewOrder {
		rawRow = Map.copyOf(rawRow);
	}

	public boolean isFilled() {
		return status.equals("FILLED");
	}

	public boolean hasPosition() {
		return !positionId.isBlank();
	}

	public boolean hasClosedPnl() {
		return closedPnl != null || netClosedPnl != null;
	}

	public String ticket() {
		if (!hasPosition()) {
			return null;
		}
		Matcher matcher = POSITION_ID.matcher(positionId);
		return matcher.matches() ? matcher.group(2) : null;
	}

	public String positionSymbol() {
		if (!hasPosition()) {
			return null;
		}
		Matcher matcher = POSITION_ID.matcher(positionId);
		return matcher.matches() ? matcher.group(1) : null;
	}

	public String fingerprint() {
		try {
			StringBuilder canonical = new StringBuilder();
			for (String column : COLUMNS) {
				String value = rawRow.get(column);
				canonical.append(value.length()).append(':').append(value);
			}
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable.", e);
		}
	}

}
