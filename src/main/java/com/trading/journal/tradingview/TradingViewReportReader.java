package com.trading.journal.tradingview;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
public class TradingViewReportReader {

	private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
	private static final int MAX_DATA_ROWS = 100_000;
	private static final int MAX_REPORTED_ERRORS = 50;
	private static final DateTimeFormatter REPORT_TIME =
			DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT);
	private static final List<String> COLUMNS = List.of(
			"Symbol", "Side", "Type", "Qty", "Filled Qty", "Limit Price", "Stop Price",
			"Avg Fill Price", "Status", "Update Time", "Position ID", "Commission",
			"Closed P&L", "Net Closed P&L", "Order ID");
	private static final Set<String> REQUIRED_COLUMNS = Set.copyOf(COLUMNS);
	private static final Pattern POSITION_ID = Pattern.compile("^([^:]+):(\\d+)$");

	public ParsedTradingViewReport read(String originalFilename, byte[] content) {
		if (content == null || content.length == 0) {
			throw new TradingViewReportException("The uploaded report is empty.");
		}
		if (content.length > MAX_FILE_BYTES) {
			throw new TradingViewReportException("The uploaded report exceeds the 10 MB limit.");
		}
		String filename = safeFilename(originalFilename);
		if (!filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
			throw new TradingViewReportException("Only TradingView CSV reports are supported.");
		}
		String csv = new String(content, StandardCharsets.UTF_8);
		if (csv.startsWith("\uFEFF")) {
			csv = csv.substring(1);
		}
		try (CSVParser parser = CSVParser.parse(
				new StringReader(csv),
				CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).get())) {
			var records = parser.iterator();
			if (!records.hasNext()) {
				throw new TradingViewReportException("The CSV report is empty.");
			}
			CSVRecord headerRecord = records.next();
			Map<String, Integer> columns = validateHeaders(headerRecord);
			List<TradingViewOrder> orders = new ArrayList<>();
			List<String> errors = new ArrayList<>();
			int errorCount = 0;
			while (records.hasNext()) {
				CSVRecord record = records.next();
				if (record.size() == 0 || record.stream().allMatch(String::isBlank)) {
					continue;
				}
				if (orders.size() + errorCount >= MAX_DATA_ROWS) {
					throw new TradingViewReportException("The report exceeds the 100,000 data-row limit.");
				}
				int line = Math.toIntExact(record.getRecordNumber());
				if (record.size() != columns.size()) {
					errorCount++;
					addError(errors, "line " + line + ": column count does not match the header");
					continue;
				}
				try {
					orders.add(mapOrder(line, record, columns));
				}
				catch (RowMappingException e) {
					errorCount++;
					addError(errors, "line " + line + ", " + e.column + ": " + e.getMessage());
				}
			}
			if (errorCount > 0) {
				throw new TradingViewReportException(
						errorCount + " malformed data row(s); no rows were imported.", errors);
			}
			if (orders.isEmpty()) {
				throw new TradingViewReportException("The report contains no data rows.");
			}
			return new ParsedTradingViewReport(filename, sha256(content), orders);
		}
		catch (IOException e) {
			throw new TradingViewReportException("The CSV report could not be parsed.");
		}
	}

	private Map<String, Integer> validateHeaders(CSVRecord header) {
		Map<String, Integer> positions = new LinkedHashMap<>();
		for (int i = 0; i < header.size(); i++) {
			String name = header.get(i).trim().replace("\uFEFF", "");
			if (positions.putIfAbsent(name, i) != null) {
				throw new TradingViewReportException("line 1: duplicate column header.");
			}
		}
		if (!positions.keySet().containsAll(REQUIRED_COLUMNS)) {
			Set<String> missing = new java.util.LinkedHashSet<>(REQUIRED_COLUMNS);
			missing.removeAll(positions.keySet());
			throw new TradingViewReportException(
					"line 1: missing required columns " + String.join(", ", missing));
		}
		return positions;
	}

	private TradingViewOrder mapOrder(int line, CSVRecord record, Map<String, Integer> columns) {
		Map<String, String> raw = new LinkedHashMap<>();
		for (String column : COLUMNS) {
			raw.put(column, record.get(columns.get(column)).trim());
		}
		String symbol = value(raw, "Symbol").toUpperCase(Locale.ROOT);
		String side = value(raw, "Side").toUpperCase(Locale.ROOT);
		String status = value(raw, "Status").toUpperCase(Locale.ROOT);
		String positionId = value(raw, "Position ID");
		String orderId = value(raw, "Order ID");
		if (symbol.isBlank() || symbol.length() > 32) {
			throw new RowMappingException("Symbol", "value is required and must not exceed 32 characters.");
		}
		if (!side.isBlank() && !side.equals("BUY") && !side.equals("SELL")) {
			throw new RowMappingException("Side", "must be BUY, SELL, or empty.");
		}
		if (!Set.of("FILLED", "CANCELLED", "REJECTED").contains(status)) {
			throw new RowMappingException("Status", "must be Filled, Cancelled, or Rejected.");
		}
		if (orderId.isBlank() || orderId.length() > 64) {
			throw new RowMappingException("Order ID", "value is required and must not exceed 64 characters.");
		}
		if (positionId.length() > 96
				|| (!positionId.isBlank() && !POSITION_ID.matcher(positionId).matches())) {
			throw new RowMappingException("Position ID", "expected SYMBOL:opening-order-id.");
		}
		if (!positionId.isBlank() && !positionId.substring(0, positionId.indexOf(':')).equalsIgnoreCase(symbol)) {
			throw new RowMappingException("Position ID", "symbol does not match Symbol.");
		}
		String type = value(raw, "Type");
		if (type.length() > 24) {
			throw new RowMappingException("Type", "value must not exceed 24 characters.");
		}
		LocalDateTime updateTime = dateTime(value(raw, "Update Time"));
		BigDecimal quantity = decimal(raw, "Qty", false);
		BigDecimal filledQuantity = decimal(raw, "Filled Qty", false);
		BigDecimal averageFillPrice = decimal(raw, "Avg Fill Price", false);
		BigDecimal closedPnl = decimal(raw, "Closed P&L", false);
		BigDecimal netClosedPnl = decimal(raw, "Net Closed P&L", false);
		if ((quantity != null && quantity.signum() < 0)
				|| (filledQuantity != null && filledQuantity.signum() < 0)) {
			throw new RowMappingException("Qty/Filled Qty", "values must not be negative.");
		}
		boolean openingOrder = !positionId.isBlank()
				&& orderId.equals(positionId.substring(positionId.indexOf(':') + 1));
		if (status.equals("FILLED") && openingOrder
				&& (side.isBlank() || filledQuantity == null || filledQuantity.signum() <= 0
						|| averageFillPrice == null || averageFillPrice.signum() <= 0)) {
			throw new RowMappingException("Filled order", "opening order requires side, filled quantity, and average fill price.");
		}
		if (status.equals("FILLED") && (closedPnl != null || netClosedPnl != null)
				&& (filledQuantity == null || filledQuantity.signum() <= 0
						|| averageFillPrice == null || averageFillPrice.signum() <= 0)) {
			throw new RowMappingException("Close order", "closed P&L requires positive filled quantity and average fill price.");
		}
		return new TradingViewOrder(
				line,
				symbol,
				side.isBlank() ? null : side,
				type.isBlank() ? null : type,
				quantity,
				filledQuantity,
				decimal(raw, "Limit Price", false),
				decimal(raw, "Stop Price", false),
				averageFillPrice,
				status,
				updateTime,
				positionId,
				decimal(raw, "Commission", false),
				closedPnl,
				netClosedPnl,
				orderId,
				raw);
	}

	private BigDecimal decimal(Map<String, String> raw, String column, boolean required) {
		String value = value(raw, column);
		if (value.isBlank()) {
			if (required) {
				throw new RowMappingException(column, "value is required.");
			}
			return null;
		}
		try {
			return new BigDecimal(value).stripTrailingZeros();
		}
		catch (NumberFormatException e) {
			throw new RowMappingException(column, "value is not a valid decimal.");
		}
	}

	private LocalDateTime dateTime(String value) {
		if (value.isBlank()) {
			throw new RowMappingException("Update Time", "value is required.");
		}
		try {
			return LocalDateTime.parse(value, REPORT_TIME);
		}
		catch (DateTimeParseException e) {
			throw new RowMappingException("Update Time", "expected yyyy-MM-dd HH:mm:ss.");
		}
	}

	private String value(Map<String, String> raw, String column) {
		return raw.getOrDefault(column, "");
	}

	private String safeFilename(String originalFilename) {
		String normalized = originalFilename == null ? "" : originalFilename.replace('\\', '/');
		String filename = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
		if (filename.isBlank()) {
			throw new TradingViewReportException("The uploaded report filename is missing.");
		}
		return filename.length() > 255 ? filename.substring(filename.length() - 255) : filename;
	}

	private String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable.", e);
		}
	}

	private void addError(List<String> errors, String message) {
		if (errors.size() < MAX_REPORTED_ERRORS) {
			errors.add(message);
		}
	}

	private static final class RowMappingException extends RuntimeException {
		private final String column;

		private RowMappingException(String column, String message) {
			super(message);
			this.column = column;
		}
	}

	public record ParsedTradingViewReport(String filename, String fileSha256, List<TradingViewOrder> orders) {
		public ParsedTradingViewReport {
			orders = List.copyOf(orders);
		}
	}

}
