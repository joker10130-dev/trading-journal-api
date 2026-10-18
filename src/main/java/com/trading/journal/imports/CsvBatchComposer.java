package com.trading.journal.imports;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

public final class CsvBatchComposer {

	private CsvBatchComposer() {
	}

	public static byte[] compose(List<String> headers, List<List<String>> rows) {
		try (var output = new java.io.ByteArrayOutputStream();
				var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
				var printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
			printer.printRecord(headers);
			for (List<String> row : rows) {
				printer.printRecord(row);
			}
			printer.flush();
			return output.toByteArray();
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to compose the combined import report.", exception);
		}
	}

}
