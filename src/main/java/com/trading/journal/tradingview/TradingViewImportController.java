package com.trading.journal.tradingview;

import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/trading-accounts/{accountId}/imports/tradingview")
public class TradingViewImportController {

	private final TradingViewImportService imports;

	public TradingViewImportController(TradingViewImportService imports) {
		this.imports = imports;
	}

	@PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public TradingViewImportService.ImportPreview preview(
			@PathVariable Long accountId,
			@RequestPart("file") MultipartFile file) {
		return imports.preview(accountId, file.getOriginalFilename(), bytes(file));
	}

	@PostMapping(path = "/batch/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public TradingViewImportService.MultiFilePreview previewFiles(
			@PathVariable Long accountId,
			@RequestPart("files") List<MultipartFile> files) {
		return imports.previewFiles(accountId, files.stream()
				.map(file -> new TradingViewImportService.BatchFile(file.getOriginalFilename(), bytes(file)))
				.toList());
	}

	@PostMapping(path = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public TradingViewImportService.MultiFileImportResult importFiles(
			@PathVariable Long accountId,
			@RequestPart("files") List<MultipartFile> files,
			@RequestParam("sha256s") List<String> sha256s) {
		return imports.importFiles(
				accountId,
				files.stream()
						.map(file -> new TradingViewImportService.BatchFile(file.getOriginalFilename(), bytes(file)))
						.toList(),
				sha256s);
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public TradingViewImportService.ImportResult importReport(
			@PathVariable Long accountId,
			@RequestPart("file") MultipartFile file,
			@RequestParam String sha256) {
		return imports.importReport(accountId, file.getOriginalFilename(), bytes(file), sha256);
	}

	private byte[] bytes(MultipartFile file) {
		try {
			return file.getBytes();
		}
		catch (IOException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The uploaded report could not be read.", e);
		}
	}

}
