package com.trading.journal.eightcap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

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
						errorCount + " malformed data row(s); no rows were imported.", errors, errorCount);
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
		Path temporaryFile = null;
		try {
			temporaryFile = Files.createTempFile("eightcap-report-", ".xlsx");
			Files.write(temporaryFile, content);
			try (OPCPackage packageFile = OPCPackage.open(temporaryFile.toFile(), PackageAccess.READ)) {
				XSSFReader reader = new XSSFReader(packageFile);
				reader.setUseReadOnlySharedStringsTable(true);
				SharedStrings sharedStrings = reader.getSharedStringsTable();
				StylesTable styles = reader.getStylesTable();
				Iterator<InputStream> sheets = reader.getSheetsData();
				if (!sheets.hasNext()) {
					throw new EightcapReportException("The XLSX report contains no worksheets.");
				}
				try (InputStream sheet = sheets.next()) {
					XlsxSheetHandler handler = new XlsxSheetHandler(sharedStrings, styles);
					XMLReader parser = XMLHelper.newXMLReader();
					parser.setContentHandler(handler);
					parser.parse(new InputSource(sheet));
					return handler.rows();
				}
			}
		}
		catch (IOException | OpenXML4JException | ParserConfigurationException | SAXException
				| IllegalArgumentException e) {
			throw new EightcapReportException("The XLSX report is invalid or cannot be opened.");
		}
		finally {
			if (temporaryFile != null) {
				try {
					Files.deleteIfExists(temporaryFile);
				}
				catch (IOException e) {
					throw new IllegalStateException("The temporary XLSX report could not be removed.", e);
				}
			}
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

	private final class XlsxSheetHandler extends DefaultHandler {

		private final SharedStrings sharedStrings;
		private final StylesTable styles;
		private final List<EightcapReportRow> rows = new ArrayList<>();
		private final List<String> errors = new ArrayList<>();
		private Map<String, Integer> columns;
		private Map<Integer, String> cells;
		private String cellType;
		private int cellIndex;
		private int styleIndex;
		private int rowNumber;
		private int errorCount;
		private String captureElement;
		private StringBuilder cellContent;

		private XlsxSheetHandler(SharedStrings sharedStrings, StylesTable styles) {
			this.sharedStrings = sharedStrings;
			this.styles = styles;
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes) {
			if ("row".equals(localName)) {
				rowNumber = Integer.parseInt(attributes.getValue("r"));
				if (rowNumber - 2 > MAX_DATA_ROWS) {
					throw new EightcapReportException("The report exceeds the 100,000 data-row limit.");
				}
				cells = new HashMap<>();
			} else if ("c".equals(localName)) {
				cellIndex = columnIndex(attributes.getValue("r"));
				cellType = attributes.getValue("t");
				String style = attributes.getValue("s");
				styleIndex = style == null ? 0 : Integer.parseInt(style);
				cellContent = new StringBuilder();
			} else if ("v".equals(localName)
					|| ("t".equals(localName) && "inlineStr".equals(cellType))) {
				captureElement = localName;
			}
		}

		@Override
		public void characters(char[] ch, int start, int length) {
			if (captureElement != null && cellContent != null) {
				cellContent.append(ch, start, length);
			}
		}

		@Override
		public void endElement(String uri, String localName, String qName) {
			if (localName.equals(captureElement)) {
				captureElement = null;
			} else if ("c".equals(localName)) {
				cells.put(cellIndex, cellValue());
				cellContent = null;
			} else if ("row".equals(localName)) {
				processRow();
				cells = null;
			}
		}

		private String cellValue() {
			String value = cellContent.toString();
			if (value.isEmpty()) {
				return "";
			}
			if ("s".equals(cellType)) {
				if (sharedStrings == null) {
					throw new IllegalArgumentException("A shared-string cell has no shared strings table.");
				}
				return sharedStrings.getItemAt(Integer.parseInt(value)).getString();
			}
			if ("inlineStr".equals(cellType)) {
				return value;
			}
			if ("b".equals(cellType)) {
				return Boolean.toString("1".equals(value));
			}
			if ("e".equals(cellType)) {
				return "";
			}
			if (isDateCell()) {
				return "@excel-date:" + value;
			}
			return value;
		}

		private boolean isDateCell() {
			if (styleIndex == 0) {
				return false;
			}
			XSSFCellStyle style = styles.getStyleAt(styleIndex);
			return DateUtil.isADateFormat(style.getDataFormat(), style.getDataFormatString());
		}

		private void processRow() {
			if (rowNumber == 2) {
				int width = cells.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
				List<String> headers = new ArrayList<>(width);
				for (int i = 0; i < width; i++) {
					headers.add(cells.getOrDefault(i, ""));
				}
				columns = validateHeaders(headers, rowNumber);
				return;
			}
			if (rowNumber < 3) {
				return;
			}
			if (columns == null) {
				throw new EightcapReportException("The XLSX report is missing its header row at line 2.");
			}
			boolean blank = true;
			for (int i = 0; i < columns.size(); i++) {
				if (!cells.getOrDefault(i, "").isBlank()) {
					blank = false;
					break;
				}
			}
			if (blank) {
				return;
			}
			try {
				rows.add(mapRow(rowNumber, name -> cells.getOrDefault(columns.get(name), ""), true));
				}
				catch (RowMappingException e) {
				errorCount++;
				addError(errors, "line " + rowNumber + ", " + e.column + ": " + e.getMessage());
			}
		}

		private int columnIndex(String reference) {
			int column = 0;
			for (int i = 0; i < reference.length(); i++) {
				char c = reference.charAt(i);
				if (!Character.isLetter(c)) {
					break;
				}
				column = column * 26 + Character.toUpperCase(c) - 'A' + 1;
			}
			return column - 1;
		}

		private List<EightcapReportRow> rows() {
			if (columns == null) {
				throw new EightcapReportException("The XLSX report is missing its header row at line 2.");
			}
			if (errorCount > 0) {
				throw new EightcapReportException(
						errorCount + " malformed data row(s); no rows were imported.", errors, errorCount);
			}
			return rows;
		}

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
