package com.trading.journal.eightcap;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class EightcapFixture {

	static final int TOTAL_ROWS = 8_299;
	static final int DUPLICATE_ROWS = 8_118;
	static final int TRADE_ROWS = 176;
	static final int CASH_ROWS = 5;
	static final String LOGIN = "synthetic-login-70508581";
	static final List<String> HEADERS = List.of(
			"Ticket", "Login", "Type", "Symbol", "Volume", "Swaps", "Commission",
			"Open Time", "Open Price", "Close Time", "Close Price", "Profit", "Currency", "Comment");

	private EightcapFixture() {
	}

	static List<List<String>> uniqueRows() {
		List<List<String>> rows = new ArrayList<>();
		for (int i = 1; i <= TRADE_ROWS; i++) {
			boolean september = i <= 22;
			String type = i <= 126 ? "buy" : "sell";
			String profit = september
					? (i < 22 ? "-30.00" : "-17.78")
					: (i == 23 ? "595.64" : "0.00");
			String swaps = i == 23 ? "10.21" : "0.00";
			String date = september ? String.format("2026-09-%02d", (i - 1) % 28 + 1) : "2026-08-01";
			rows.add(List.of(
					"T-" + String.format("%04d", i),
					LOGIN,
					type,
					"XAUUSD",
					"0.05",
					swaps,
					"0.00",
					date + " 09:00:01",
					"4375.59000",
					date + " 14:41:50",
					"4387.16000",
					profit,
					"USD",
					"STOP GTC COMPLETED"));
		}
		rows.add(cashRow("C-0001", "2026-09-03", "WITHDRAWAL", "-250.00"));
		rows.add(cashRow("C-0002", "2026-09-20", "DEPOSIT", "500.00"));
		rows.add(cashRow("C-0003", "2026-05-12", "WITHDRAWAL", "-100.00"));
		rows.add(cashRow("C-0004", "2026-07-22", "CREDIT", "75.00"));
		rows.add(cashRow("C-0005", "2026-10-04", "CREDIT", "25.00"));
		return List.copyOf(rows);
	}

	static List<List<String>> septemberRows() {
		List<List<String>> rows = uniqueRows();
		List<List<String>> septemberRows = new ArrayList<>(rows.subList(0, 22));
		septemberRows.addAll(rows.subList(TRADE_ROWS, TRADE_ROWS + 2));
		return List.copyOf(septemberRows);
	}

	static byte[] csvBytes(List<List<String>> rows, boolean paddedToFullSize) throws IOException {
		StringWriter writer = new StringWriter();
		try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
			printer.printRecord("Trades Report");
			printer.printRecord(HEADERS);
			if (paddedToFullSize) {
				int[] copies = new int[rows.size()];
				for (int i = 0; i < copies.length; i++) {
					copies[i] = i < 154 ? 46 : 45;
				}
				for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
					for (int i = 0; i < copies[rowIndex]; i++) {
						printer.printRecord(rows.get(rowIndex));
					}
				}
			}
			else {
				rows.forEach(row -> {
					try {
						printer.printRecord(row);
					}
					catch (IOException e) {
						throw new FixtureWriteException(e);
					}
				});
			}
		}
		return writer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
	}

	static byte[] xlsxBytes(List<List<String>> rows, boolean paddedToFullSize) throws IOException {
		try (XSSFWorkbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("data");
			sheet.createRow(0).createCell(0).setCellValue("Trades Report");
			writeRow(sheet.createRow(1), HEADERS);
			if (paddedToFullSize) {
				int[] copies = new int[rows.size()];
				for (int i = 0; i < copies.length; i++) {
					copies[i] = i < 154 ? 46 : 45;
				}
				int rowNumber = 2;
				for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
					for (int i = 0; i < copies[rowIndex]; i++) {
						writeRow(sheet.createRow(rowNumber++), rows.get(rowIndex));
					}
				}
			}
			else {
				int rowNumber = 2;
				for (List<String> values : rows) {
					writeRow(sheet.createRow(rowNumber++), values);
				}
			}
			try (var output = new java.io.ByteArrayOutputStream()) {
				workbook.write(output);
				return output.toByteArray();
			}
		}
	}

	private static void writeRow(Row row, List<String> values) {
		for (int i = 0; i < values.size(); i++) {
			row.createCell(i).setCellValue(values.get(i));
		}
	}

	private static List<String> cashRow(String ticket, String date, String type, String amount) {
		return List.of(
				ticket,
				LOGIN,
				"",
				"",
				"",
				"0.00",
				"0.00",
				"",
				"",
				date + " 10:00:00",
				"",
				amount,
				"USD",
				type + " TEST TRANSACTION");
	}

	private static final class FixtureWriteException extends RuntimeException {
		private FixtureWriteException(IOException cause) {
			super(cause);
		}
	}

}
