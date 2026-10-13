package com.trading.journal.eightcap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class EightcapReportReaderTests {

	@Test
	void readsNumericExcelDatesWithStreamingReader() throws Exception {
		LocalDateTime openTime = LocalDateTime.of(2026, 9, 25, 4, 1, 2);
		LocalDateTime closeTime = LocalDateTime.of(2026, 9, 25, 21, 6, 17);
		byte[] workbookBytes;
		try (XSSFWorkbook workbook = new XSSFWorkbook()) {
			var sheet = workbook.createSheet("data");
			sheet.createRow(0).createCell(0).setCellValue("Trades Report");
			var header = sheet.createRow(1);
			for (int i = 0; i < EightcapFixture.HEADERS.size(); i++) {
				header.createCell(i).setCellValue(EightcapFixture.HEADERS.get(i));
			}
			CellStyle dateStyle = workbook.createCellStyle();
			dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
			var row = sheet.createRow(2);
			row.createCell(0).setCellValue("SANITIZED-DATE-1");
			row.createCell(1).setCellValue("");
			row.createCell(2).setCellValue("buy");
			row.createCell(3).setCellValue("XAUUSD");
			row.createCell(4).setCellValue(0.01);
			row.createCell(5).setCellValue(0);
			row.createCell(6).setCellValue(0);
			var openCell = row.createCell(7);
			openCell.setCellValue(excelSerial(openTime));
			openCell.setCellStyle(dateStyle);
			row.createCell(8).setCellValue(4400);
			var closeCell = row.createCell(9);
			closeCell.setCellValue(excelSerial(closeTime));
			closeCell.setCellStyle(dateStyle);
			row.createCell(10).setCellValue(4410);
			row.createCell(11).setCellValue(1);
			row.createCell(12).setCellValue("USD");
			row.createCell(13).setCellValue("SANITIZED FIXTURE");
			try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				workbook.write(output);
				workbookBytes = output.toByteArray();
			}
		}

		EightcapReportReader.ParsedEightcapReport report =
				new EightcapReportReader().read("numeric-dates.xlsx", workbookBytes);
		assertEquals(1, report.rows().size());
		assertEquals(openTime, report.rows().getFirst().openTime());
		assertEquals(closeTime, report.rows().getFirst().closeTime());
	}

	private double excelSerial(LocalDateTime value) {
		long days = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), value.toLocalDate());
		long seconds = value.toLocalTime().toSecondOfDay();
		return days + seconds / 86_400.0;
	}

}
