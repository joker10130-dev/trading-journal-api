package com.trading.journal.eightcap;

import java.io.ByteArrayInputStream;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

@Component
public class EightcapReportReader {

	private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
	private static final int MAX_DATA_ROWS = 100_000;
	private static final int MAX_REPORTED_ERRORS = 50;
	private static final long MAX_XLSX_EXPANDED_BYTES = 100L * 1024 * 1024;
	private static final int MAX_XLSX_ENTRIES = 1_000;
	private static final double MAX_XLSX_ENTRY_RATIO = 100.0;
	private static final DateTimeFormatter REPORT_TIME =
			DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT);
	private static final List<String> COLUMNS = List.of(
			"Ticket", "Login", "Type", "Symbol", "Volume", "Swaps", "Commission",
			"Open Time", "Open Price", "Close Time", "Close Price", "Profit", "Currency", "Comment");
	private static final Set<String> REQUIRED_COLUMNS = Set.copyOf(COLUMNS);

	public ParsedEightcapReport read(String originalFilename, byte[] content) {
		if (content == null || content.length == 0) {
			throw new EightcapReportException("The uploaded report is empty.");
		}
		if (content.length > MAX_FILE_BYTES) {
			throw new EightcapReportException("The uploaded report exceeds the 10 MB limit.");
		}
		String filename = safeFilename(originalFilename);
		String extension = extension(filename);
		List<EightcapReportRow> rows = switch (extension) {
			case "csv" -> readCsv(content);
			case "xlsx" -> readXlsx(content);
			default -> throw new EightcapReportException("Only Eightcap CSV and XLSX reports are supported.");
		};
		if (rows.size() > MAX_DATA_ROWS) {
			throw new EightcapReportException("The report exceeds the 100,000 data-row limit.");
		}
		if (rows.isEmpty()) {
			throw new EightcapReportException("The report contains no data rows.");
		}
		return new ParsedEightcapReport(
				filename,
				HexFormat.of().formatHex(sha256(content)),
				rows);
	}

	private List<EightcapReportRow> readCsv(byte[] content) {
		String csv = new String(content, StandardCharsets.UTF_8);
		if (!csv.isEmpty() && csv.charAt(0) == '\uFEFF') {
			csv = csv.substring(1);
		}
		try (CSVParser parser = CSVParser.parse(
				new StringReader(csv),
				CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).get())) {
			Iterator<CSVRecord> records = parser.iterator();
			if (!records.hasNext()) {
				throw new EightcapReportException("The CSV report is empty.");
			}
			CSVRecord headerRecord = records.next();
			List<String> headers = recordValues(headerRecord);
			if (!hasRequiredHeaders(headers) && records.hasNext()) {
				headerRecord = records.next();
				headers = recordValues(headerRecord);
			}
			Map<String, Integer> columns = validateHeaders(headers, Math.toIntExact(headerRecord.getRecordNumber()));
			List<EightcapReportRow> rows = new ArrayList<>();
			List<String> errors = new ArrayList<>();
			int errorCount = 0;
			int dataRowCount = 0;
			while (records.hasNext()) {
				CSVRecord record = records.next();
				if (record.size() == 0 || record.stream().allMatch(String::isBlank)) {
					continue;
				}
				if (++dataRowCount > MAX_DATA_ROWS) {
					throw new EightcapReportException("The report exceeds the 100,000 data-row limit.");
				}
				int lineNumber = Math.toIntExact(record.getRecordNumber());
				if (record.size() != columns.size()) {
					errorCount++;
					addError(errors, "line " + lineNumber + ": column count does not match the header");
					continue;
				}
				try {
					rows.add(mapRow(lineNumber, name -> record.get(columns.get(name)), false));
				}
				catch (RowMappingException e) {
					errorCount++;
					addError(errors, "line " + lineNumber + ", " + e.column + ": " + e.getMessage());
				}
			}
			if (errorCount > 0) {
				throw new EightcapReportException(
						errorCount + " malformed data row(s); no rows were imported.", errors);
			}
			return rows;
		}
		catch (IOException e) {
			throw new EightcapReportException("The CSV report could not be parsed.");
		}
	}

	private List<String> recordValues(CSVRecord record) {
		List<String> values = new ArrayList<>(record.size());
		record.forEach(values::add);
		return values;
	}

	private boolean hasRequiredHeaders(List<String> headers) {
		Set<String> names = headers.stream()
				.map(header -> header.trim().replace("\uFEFF", ""))
				.collect(java.util.stream.Collectors.toSet());
		return names.containsAll(REQUIRED_COLUMNS);
	}

	private List<EightcapReportRow> readXlsx(byte[] content) {
		validateXlsxArchive(content);
		try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
			if (workbook.getNumberOfSheets() == 0) {
				throw new EightcapReportException("The XLSX report contains no worksheets.");
			}
			Sheet sheet = workbook.getSheetAt(0);
			Row header = sheet.getRow(1);
			if (header == null) {
				throw new EightcapReportException("The XLSX report is missing its header row at line 2.");
			}
			Map<String, Integer> columns = validateHeaders(
					readHeader(header),
					header.getRowNum() + 1);
			if (sheet.getLastRowNum() - 1 > MAX_DATA_ROWS) {
				throw new EightcapReportException("The report exceeds the 100,000 data-row limit.");
			}
			List<EightcapReportRow> rows = new ArrayList<>();
			List<String> errors = new ArrayList<>();
			int errorCount = 0;
			for (int rowIndex = 2; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
				Row row = sheet.getRow(rowIndex);
				if (row == null || isBlank(row, columns.size())) {
					continue;
				}
				int lineNumber = rowIndex + 1;
				try {
					rows.add(mapRow(lineNumber, name -> readCell(row.getCell(columns.get(name))), true));
				}
				catch (RowMappingException e) {
					errorCount++;
					addError(errors, "line " + lineNumber + ", " + e.column + ": " + e.getMessage());
				}
			}
			if (errorCount > 0) {
				throw new EightcapReportException(
						errorCount + " malformed data row(s); no rows were imported.", errors);
			}
			return rows;
		}
		catch (IOException | EncryptedDocumentException | IllegalArgumentException e) {
			throw new EightcapReportException("The XLSX report is invalid or cannot be opened.");
		}
	}

	private void validateXlsxArchive(byte[] content) {
		long expandedBytes = 0;
		int entries = 0;
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
			ZipEntry entry;
			byte[] buffer = new byte[8_192];
			while ((entry = zip.getNextEntry()) != null) {
				entries++;
				if (entries > MAX_XLSX_ENTRIES) {
					throw new EightcapReportException("The XLSX report contains too many archive entries.");
				}
				long entryBytes = 0;
				int read;
				while ((read = zip.read(buffer)) != -1) {
					entryBytes += read;
					expandedBytes += read;
					if (expandedBytes > MAX_XLSX_EXPANDED_BYTES) {
						throw new EightcapReportException("The XLSX report expands beyond the 100 MB safety limit.");
					}
				}
				long compressedBytes = entry.getCompressedSize();
				if (compressedBytes > 0 && (double) entryBytes / compressedBytes > MAX_XLSX_ENTRY_RATIO) {
					throw new EightcapReportException("The XLSX report contains an excessive compression ratio.");
				}
				zip.closeEntry();
			}
		}
		catch (IOException e) {
			throw new EightcapReportException("The XLSX report is not a valid ZIP archive.");
		}
		if (entries == 0) {
			throw new EightcapReportException("The XLSX report is not a valid ZIP archive.");
		}
	}

	private List<String> readHeader(Row row) {
		List<String> header = new ArrayList<>();
		for (int i = 0; i < row.getLastCellNum(); i++) {
			header.add(readCell(row.getCell(i)));
		}
		return header;
	}

	private Map<String, Integer> validateHeaders(Iterable<String> actualHeaders, int lineNumber) {
		Map<String, Integer> positions = new LinkedHashMap<>();
		int index = 0;
		for (String actualHeader : actualHeaders) {
			String normalized = actualHeader == null ? "" : actualHeader.trim().replace("\uFEFF", "");
			if (positions.putIfAbsent(normalized, index++) != null) {
				throw new EightcapReportException("line " + lineNumber + ": duplicate column header.");
			}
		}
		if (!positions.keySet().containsAll(REQUIRED_COLUMNS)) {
			Set<String> missing = new java.util.LinkedHashSet<>(REQUIRED_COLUMNS);
			missing.removeAll(positions.keySet());
			throw new EightcapReportException(
					"line " + lineNumber + ": missing required columns " + String.join(", ", missing));
		}
		return positions;
	}

	private EightcapReportRow mapRow(int line, CellReader reader, boolean excel) {
		String ticket = text(reader, "Ticket");
		String login = text(reader, "Login");
		String type = text(reader, "Type").toLowerCase(Locale.ROOT);
		String symbol = text(reader, "Symbol").toUpperCase(Locale.ROOT);
		String currency = text(reader, "Currency").toUpperCase(Locale.ROOT);
		String comment = text(reader, "Comment");

		if (ticket.isBlank()) {
			throw new RowMappingException("Ticket", "value is required.");
		}
		if (ticket.length() > 64 || login.length() > 64 || symbol.length() > 32
				|| currency.length() != 3 || comment.length() > 512) {
			throw new RowMappingException("row", "one or more text values exceed their supported length.");
		}

		BigDecimal profit = decimal(reader, "Profit", true);
		BigDecimal swaps = decimal(reader, "Swaps", false);
		BigDecimal commission = decimal(reader, "Commission", false);
		if (type.isBlank() || symbol.isBlank()) {
			return new EightcapReportRow(
					line, ticket, login, type, symbol,
					decimal(reader, "Volume", false),
					swaps, commission,
					dateTime(reader, "Open Time", excel, false),
					decimal(reader, "Open Price", false),
					dateTime(reader, "Close Time", excel, false),
					decimal(reader, "Close Price", false),
					profit, currency, comment);
		}

		if (!type.equals("buy") && !type.equals("sell")) {
			throw new RowMappingException("Type", "must be BUY or SELL.");
		}
		BigDecimal volume = decimal(reader, "Volume", true);
		BigDecimal openPrice = decimal(reader, "Open Price", true);
		BigDecimal closePrice = decimal(reader, "Close Price", true);
		LocalDateTime openTime = dateTime(reader, "Open Time", excel, true);
		LocalDateTime closeTime = dateTime(reader, "Close Time", excel, true);
		if (volume.signum() <= 0 || openPrice.signum() <= 0 || closePrice.signum() <= 0) {
			throw new RowMappingException("Volume/Open Price/Close Price", "values must be greater than zero.");
		}
		return new EightcapReportRow(
				line, ticket, login, type, symbol, volume, swaps, commission,
				openTime, openPrice, closeTime, closePrice, profit, currency, comment);
	}

	private String text(CellReader reader, String column) {
		String value = reader.read(column);
		return value == null ? "" : value.trim();
	}

	private BigDecimal decimal(CellReader reader, String column, boolean required) {
		String value = text(reader, column);
		if (value.isEmpty()) {
			if (required) {
				throw new RowMappingException(column, "value is required.");
			}
			return BigDecimal.ZERO;
		}
		try {
			return new BigDecimal(value).stripTrailingZeros();
		}
		catch (NumberFormatException e) {
			throw new RowMappingException(column, "value is not a valid decimal.");
		}
	}

	private LocalDateTime dateTime(CellReader reader, String column, boolean excel, boolean required) {
		String value = text(reader, column);
		if (value.isEmpty()) {
			if (required) {
				throw new RowMappingException(column, "value is required.");
			}
			return null;
		}
		if (excel && value.startsWith("@excel-date:")) {
			try {
				return DateUtil.getLocalDateTime(Double.parseDouble(value.substring(12)));
			}
			catch (IllegalArgumentException e) {
				throw new RowMappingException(column, "value is not a valid Excel date.");
			}
		}
		try {
			return LocalDateTime.parse(value, REPORT_TIME);
		}
		catch (DateTimeParseException e) {
			throw new RowMappingException(column, "expected yyyy-MM-dd HH:mm:ss.");
		}
	}

	private String readCell(Cell cell) {
		if (cell == null || cell.getCellType() == CellType.BLANK) {
			return "";
		}
		CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
		if (type == CellType.NUMERIC) {
			double numeric = cell.getNumericCellValue();
			if (DateUtil.isCellDateFormatted(cell)) {
				return "@excel-date:" + numeric;
			}
			return BigDecimal.valueOf(numeric).stripTrailingZeros().toPlainString();
		}
		if (type == CellType.STRING) {
			return cell.getStringCellValue();
		}
		if (type == CellType.BOOLEAN) {
			return Boolean.toString(cell.getBooleanCellValue());
		}
		return "";
	}

	private boolean isBlank(Row row, int cellCount) {
		for (int i = 0; i < cellCount; i++) {
			if (!readCell(row.getCell(i)).isBlank()) {
				return false;
			}
		}
		return true;
	}

	private byte[] sha256(byte[] content) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(content);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable.", e);
		}
	}

	private String safeFilename(String originalFilename) {
		String normalized = originalFilename == null ? "" : originalFilename.replace('\\', '/');
		String filename = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
		if (filename.isBlank()) {
			throw new EightcapReportException("The uploaded report filename is missing.");
		}
		return filename.length() > 255 ? filename.substring(filename.length() - 255) : filename;
	}

	private String extension(String filename) {
		int dot = filename.lastIndexOf('.');
		return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	private void addError(List<String> errors, String message) {
		if (errors.size() < MAX_REPORTED_ERRORS) {
			errors.add(message);
		}
	}

	@FunctionalInterface
	private interface CellReader {
		String read(String column);
	}

	private static final class RowMappingException extends RuntimeException {
		private final String column;

		private RowMappingException(String column, String message) {
			super(message);
			this.column = column;
		}
	}

	public record ParsedEightcapReport(String filename, String fileSha256, List<EightcapReportRow> rows) {
		public ParsedEightcapReport {
			rows = List.copyOf(rows);
		}
	}

}
