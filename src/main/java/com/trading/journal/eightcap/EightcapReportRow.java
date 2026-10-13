package com.trading.journal.eightcap;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

public record EightcapReportRow(
		int lineNumber,
		String ticket,
		String login,
		String type,
		String symbol,
		BigDecimal volume,
		BigDecimal swaps,
		BigDecimal commission,
		LocalDateTime openTime,
		BigDecimal openPrice,
		LocalDateTime closeTime,
		BigDecimal closePrice,
		BigDecimal profit,
		String currency,
		String comment) {

	public boolean isCashTransaction() {
		return type.isBlank() || symbol.isBlank();
	}

	public String transactionType() {
		String normalizedComment = comment.toUpperCase(java.util.Locale.ROOT);
		if (normalizedComment.contains("WITHDRAWAL")) {
			return "WITHDRAWAL";
		}
		if (normalizedComment.contains("DEPOSIT")) {
			return "DEPOSIT";
		}
		return "CREDIT";
	}

	public String side() {
		return switch (type.toLowerCase(java.util.Locale.ROOT)) {
			case "buy" -> "LONG";
			case "sell" -> "SHORT";
			default -> throw new IllegalStateException("Validated report row has an unsupported trade type.");
		};
	}

	public BigDecimal netPnl() {
		return profit.add(swaps).add(commission);
	}

	public String fingerprint() {
		String canonical = String.join("\u001f",
				ticket,
				login,
				type.toLowerCase(java.util.Locale.ROOT),
				symbol.toUpperCase(java.util.Locale.ROOT),
				decimal(volume),
				decimal(swaps),
				decimal(commission),
				time(openTime),
				decimal(openPrice),
				time(closeTime),
				decimal(closePrice),
				decimal(profit),
				currency.toUpperCase(java.util.Locale.ROOT),
				comment);
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable.", e);
		}
	}

	private static String decimal(BigDecimal value) {
		return value == null ? "" : value.stripTrailingZeros().toPlainString();
	}

	private static String time(LocalDateTime value) {
		return value == null ? "" : value.toString();
	}

}
