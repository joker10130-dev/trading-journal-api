package com.trading.journal.eightcap;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.trading.journal.account.AccountTransactionEntity;
import com.trading.journal.account.AccountTransactionRepository;
import com.trading.journal.account.CurrentUserService;
import com.trading.journal.account.TradingAccountEntity;
import com.trading.journal.account.TradingAccountRepository;
import com.trading.journal.account.UserEntity;
import com.trading.journal.eightcap.EightcapReportReader.ParsedEightcapReport;
import com.trading.journal.imports.ImportBatchEntity;
import com.trading.journal.imports.ImportBatchRepository;
import com.trading.journal.imports.CsvBatchComposer;
import com.trading.journal.trade.TradeCloseEntity;
import com.trading.journal.trade.TradeCloseRepository;
import com.trading.journal.trade.TradeEntity;
import com.trading.journal.trade.TradeRepository;
import com.trading.journal.trade.TradeOrderEntity;
import com.trading.journal.trade.TradeOrderRepository;
import com.trading.journal.trade.TradeManualRService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EightcapImportService {

	private static final int MAX_BATCH_FILES = 5;
	private static final long MAX_BATCH_BYTES = 50L * 1024 * 1024;
	private static final List<String> BATCH_CSV_HEADERS = List.of(
			"Ticket", "Login", "Type", "Symbol", "Volume", "Swaps", "Commission",
			"Open Time", "Open Price", "Close Time", "Close Price", "Profit", "Currency", "Comment");
	private static final DateTimeFormatter BATCH_TIME_FORMAT =
			DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT);

	private final CurrentUserService currentUserService;
	private final TradingAccountRepository accounts;
	private final ImportBatchRepository importBatches;
	private final TradeRepository trades;
	private final TradeCloseRepository tradeCloses;
	private final TradeOrderRepository tradeOrders;
	private final TradeManualRService manualRService;
	private final AccountTransactionRepository accountTransactions;
	private final EightcapReportReader reader;

	public EightcapImportService(
			CurrentUserService currentUserService,
			TradingAccountRepository accounts,
			ImportBatchRepository importBatches,
			TradeRepository trades,
			TradeCloseRepository tradeCloses,
			TradeOrderRepository tradeOrders,
			TradeManualRService manualRService,
			AccountTransactionRepository accountTransactions,
			EightcapReportReader reader) {
		this.currentUserService = currentUserService;
		this.accounts = accounts;
		this.importBatches = importBatches;
		this.trades = trades;
		this.tradeCloses = tradeCloses;
		this.tradeOrders = tradeOrders;
		this.manualRService = manualRService;
		this.accountTransactions = accountTransactions;
		this.reader = reader;
	}

	@Transactional(readOnly = true)
	public ImportPreview preview(Long accountId, String filename, byte[] content) {
		UserEntity user = currentUserService.requireCurrentUser();
		TradingAccountEntity account = requireOwnedAccount(accountId, user.getId());
		ParsedEightcapReport report = reader.read(filename, content);
		List<EightcapReportRow> uniqueRows = deduplicate(report.rows());
		validateRows(account, uniqueRows);
		Assessment assessment = assess(account, uniqueRows);
		return new ImportPreview(
				account.getId(),
				report.filename(),
				report.fileSha256(),
				report.rows().size(),
				report.rows().size() - uniqueRows.size(),
				uniqueRows.size(),
				assessment.newTrades(),
				assessment.existingTrades(),
				assessment.newTradeCloses(),
				assessment.alreadyImportedTradeCloses(),
				assessment.nonTradeRows(),
				assessment.newCashTransactions(),
				assessment.alreadyImportedCashTransactions(),
				assessment.loginMismatch(),
				assessment.nonTradeRows(),
				0,
				warnings(
						report.rows().size(),
						report.rows().size() - uniqueRows.size(),
						assessment.loginMismatch(),
						assessment.noExistingTradeOverlap()));
	}

	@Transactional(readOnly = true)
	public MultiFilePreview previewFiles(Long accountId, List<BatchFile> files) {
		UserEntity user = currentUserService.requireCurrentUser();
		requireOwnedAccount(accountId, user.getId());
		ComposedBatch composed = composeBatch(files);
		ImportPreview preview = preview(accountId, composed.filename(), composed.content());
		return new MultiFilePreview(composed.files(), preview);
	}

	@Transactional
	public ImportResult importReport(
			Long accountId,
			String filename,
			byte[] content,
			String expectedSha256,
			boolean acknowledgeLoginMismatch) {
		UserEntity user = currentUserService.requireCurrentUser();
		TradingAccountEntity account = accounts.findByIdAndUserIdForUpdate(accountId, user.getId())
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "Trading account was not found."));
		ParsedEightcapReport report = reader.read(filename, content);
		if (expectedSha256 == null
				|| !MessageDigest.isEqual(
						report.fileSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
						expectedSha256.trim().toLowerCase(Locale.ROOT)
								.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
			throw new ResponseStatusException(
					HttpStatus.CONFLICT, "The uploaded file changed after preview. Preview it again before importing.");
		}
		List<EightcapReportRow> uniqueRows = deduplicate(report.rows());
		validateRows(account, uniqueRows);
		Assessment assessment = assess(account, uniqueRows);
		if (assessment.loginMismatch() && !acknowledgeLoginMismatch) {
			throw new ResponseStatusException(
					HttpStatus.CONFLICT, "The report login does not match this trading account; explicit confirmation is required.");
		}

		int rowsToImport = assessment.newTradeCloses() + assessment.newCashTransactions();
		ImportBatchEntity batch = importBatches.saveAndFlush(new ImportBatchEntity(
				user.getId(),
				account.getId(),
				report.filename(),
				report.fileSha256(),
				report.rows().size(),
				report.rows().size() - uniqueRows.size(),
				rowsToImport,
				assessment.nonTradeRows()));

		Map<String, TradeEntity> tradeByTicket = new HashMap<>(assessment.existingTradeByTicket());
		Set<String> existingCloseFingerprints = new HashSet<>(assessment.existingCloseFingerprints());
		Set<String> existingCashFingerprints = new HashSet<>(assessment.existingCashFingerprints());
		Map<String, EightcapReportRow> reportRowByTicket = uniqueRows.stream()
				.filter(row -> !row.isCashTransaction())
				.collect(Collectors.toMap(
						EightcapReportRow::ticket,
						row -> row,
						(first, ignored) -> first));
		for (TradeEntity trade : tradeByTicket.values()) {
			if (!trade.isHasTradesReport()) {
				EightcapReportRow row = reportRowByTicket.get(trade.getTicket());
				if (row != null) {
					List<TradeCloseEntity> tradingViewCloses =
							tradeCloses.findAllByTradingAccountIdAndTradeIdAndSource(
									account.getId(), trade.getId(), "TRADINGVIEW");
					List<TradeCloseEntity> manualCloses =
							tradeCloses.findAllByTradingAccountIdAndTradeIdAndSource(
									account.getId(), trade.getId(), "MANUAL");
					java.util.stream.Stream.concat(tradingViewCloses.stream(), manualCloses.stream())
							.map(TradeCloseEntity::getSourceFingerprint)
							.forEach(existingCloseFingerprints::remove);
					tradeCloses.deleteAll(tradingViewCloses);
					tradeCloses.deleteAll(manualCloses);
					trade.replaceWithTradesReport(
							row.symbol(),
							row.side(),
							row.openTime(),
							toUtc(row.openTime(), account.getTradesReportUtcOffsetMinutes()),
							row.openPrice());
				}
			}
		}
		int importedTradeCloses = 0;
		int importedCashTransactions = 0;
		for (EightcapReportRow row : uniqueRows) {
			if (row.isCashTransaction()) {
				if (existingCashFingerprints.add(row.fingerprint())) {
					accountTransactions.save(new AccountTransactionEntity(
							user.getId(),
							account.getId(),
							batch.getId(),
							row.fingerprint(),
							row.transactionType(),
							row.closeTime() != null ? row.closeTime() : row.openTime(),
							row.profit(),
							row.currency(),
							row.comment()));
					importedCashTransactions++;
				}
				continue;
			}

			TradeEntity trade = tradeByTicket.get(row.ticket());
			if (trade == null) {
				trade = new TradeEntity(
						user.getId(),
						account.getId(),
						row.ticket(),
						row.symbol(),
						row.side(),
						row.openTime(),
						toUtc(row.openTime(), account.getTradesReportUtcOffsetMinutes()),
						row.openPrice());
				tradeByTicket.put(row.ticket(), trade);
			}
			if (!existingCloseFingerprints.add(row.fingerprint())) {
				continue;
			}

			trade.addClose(row.volume(), row.profit(), row.swaps().add(row.commission()), row.netPnl());
			if (trade.getId() == null) {
				trade = trades.saveAndFlush(trade);
				tradeByTicket.put(row.ticket(), trade);
			}
			tradeCloses.save(new TradeCloseEntity(
					user.getId(),
					account.getId(),
					trade.getId(),
					batch.getId(),
					row.fingerprint(),
					row.volume(),
					row.closeTime(),
					toUtc(row.closeTime(), account.getTradesReportUtcOffsetMinutes()),
					row.closePrice(),
					row.profit(),
					row.swaps(),
					row.commission(),
					row.netPnl()));
			importedTradeCloses++;
		}
		List<String> positionIds = tradeByTicket.values().stream()
				.filter(trade -> reportRowByTicket.containsKey(trade.getTicket()))
				.map(trade -> trade.getSymbol() + ":" + trade.getTicket())
				.distinct()
				.toList();
		if (!positionIds.isEmpty()) {
			for (TradeOrderEntity order : tradeOrders.findAllByTradingAccountIdAndPositionIdIn(
					account.getId(), positionIds)) {
				TradeEntity trade = tradeByTicket.get(order.getPositionId().substring(
						order.getPositionId().lastIndexOf(':') + 1));
				if (trade != null) {
					order.attachToTrade(trade.getId());
					trade.markHasTradingViewData();
				}
			}
		}
		tradeByTicket.values().forEach(manualRService::redistributeManualR);

		return new ImportResult(
				batch.getId(),
				report.rows().size(),
				report.rows().size() - uniqueRows.size(),
				importedTradeCloses,
				importedCashTransactions,
				assessment.existingTrades(),
				assessment.alreadyImportedTradeCloses(),
				assessment.alreadyImportedCashTransactions(),
				assessment.nonTradeRows(),
				warnings(
						report.rows().size(),
						report.rows().size() - uniqueRows.size(),
						assessment.loginMismatch(),
						assessment.noExistingTradeOverlap()));
	}

	@Transactional
	public MultiFileImportResult importFiles(
			Long accountId,
			List<BatchFile> files,
			List<String> expectedSha256s,
			boolean acknowledgeLoginMismatch) {
		UserEntity user = currentUserService.requireCurrentUser();
		accounts.findByIdAndUserIdForUpdate(accountId, user.getId())
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "Trading account was not found."));
		ComposedBatch composed = composeBatch(files);
		if (expectedSha256s == null || expectedSha256s.size() != composed.files().size()) {
			throw new ResponseStatusException(
					HttpStatus.CONFLICT, "Preview every file in the batch before importing.");
		}
		for (int i = 0; i < composed.files().size(); i++) {
			String expectedSha256 = expectedSha256s.get(i);
			String actualSha256 = composed.files().get(i).fileSha256();
			if (expectedSha256 == null || !MessageDigest.isEqual(
					actualSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
					expectedSha256.trim().toLowerCase(Locale.ROOT)
							.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
				throw new ResponseStatusException(
						HttpStatus.CONFLICT,
						"One or more uploaded files changed after preview. Preview the batch again before importing.");
			}
		}
		ImportResult result = importReport(
				accountId,
				composed.filename(),
				composed.content(),
				composed.fileSha256(),
				acknowledgeLoginMismatch);
		return new MultiFileImportResult(composed.files(), result);
	}

	private ComposedBatch composeBatch(List<BatchFile> files) {
		if (files == null || files.isEmpty() || files.size() > MAX_BATCH_FILES) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST, "A batch must contain between 1 and 5 Eightcap files.");
		}
		long totalBytes = files.stream().mapToLong(file -> file.content().length).sum();
		if (totalBytes > MAX_BATCH_BYTES) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST, "The combined upload exceeds the 50 MB batch limit.");
		}

		List<List<String>> rows = new ArrayList<>();
		List<BatchFileInfo> fileInfos = new ArrayList<>();
		for (BatchFile file : files) {
			ParsedEightcapReport report = reader.read(file.filename(), file.content());
			fileInfos.add(new BatchFileInfo(report.filename(), report.fileSha256(), report.rows().size()));
			for (EightcapReportRow row : report.rows()) {
				rows.add(List.of(
						row.ticket(),
						row.login(),
						row.type(),
						row.symbol(),
						decimal(row.volume()),
						decimal(row.swaps()),
						decimal(row.commission()),
						time(row.openTime()),
						decimal(row.openPrice()),
						time(row.closeTime()),
						decimal(row.closePrice()),
						decimal(row.profit()),
						row.currency(),
						row.comment()));
			}
		}
		String filename = files.size() + "-files.csv";
		byte[] combined = CsvBatchComposer.compose(BATCH_CSV_HEADERS, rows);
		return new ComposedBatch(
				filename,
				combined,
				java.util.HexFormat.of().formatHex(sha256(combined)),
				List.copyOf(fileInfos));
	}

	private String decimal(java.math.BigDecimal value) {
		return value == null ? "" : value.stripTrailingZeros().toPlainString();
	}

	private String time(java.time.LocalDateTime value) {
		return value == null ? "" : value.format(BATCH_TIME_FORMAT);
	}

	private byte[] sha256(byte[] content) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(content);
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private Assessment assess(TradingAccountEntity account, List<EightcapReportRow> rows) {
		List<EightcapReportRow> tradeRows = rows.stream().filter(row -> !row.isCashTransaction()).toList();
		List<EightcapReportRow> cashRows = rows.stream().filter(EightcapReportRow::isCashTransaction).toList();
		Set<String> tickets = tradeRows.stream().map(EightcapReportRow::ticket)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, TradeEntity> existingTrades = tickets.isEmpty()
				? Map.of()
				: trades.findAllByTradingAccountIdAndTicketIn(account.getId(), tickets).stream()
						.collect(Collectors.toMap(TradeEntity::getTicket, trade -> trade));

		Set<String> existingCloseFingerprints = new HashSet<>();
		if (!existingTrades.isEmpty()) {
			List<Long> tradeIds = existingTrades.values().stream().map(TradeEntity::getId).toList();
			existingCloseFingerprints.addAll(tradeCloses
					.findAllByTradingAccountIdAndTradeIdIn(account.getId(), tradeIds).stream()
					.map(TradeCloseEntity::getSourceFingerprint)
					.toList());
		}
		Set<String> existingCashFingerprints = new HashSet<>();
		if (!cashRows.isEmpty()) {
			existingCashFingerprints.addAll(accountTransactions
					.findAllByTradingAccountIdAndSourceFingerprintIn(
							account.getId(),
							cashRows.stream().map(EightcapReportRow::fingerprint).toList()).stream()
					.map(AccountTransactionEntity::getSourceFingerprint)
					.toList());
		}

		int newTrades = (int) tickets.stream().filter(ticket -> !existingTrades.containsKey(ticket)).count();
		int existingTradesCount = tickets.size() - newTrades;
		int newTradeCloses = (int) tradeRows.stream()
				.filter(row -> !existingCloseFingerprints.contains(row.fingerprint())).count();
		int newCashTransactions = (int) cashRows.stream()
				.filter(row -> !existingCashFingerprints.contains(row.fingerprint())).count();
		boolean accountHasTrades = trades.existsByTradingAccountId(account.getId());
		boolean tradeOverlap = !existingTrades.isEmpty();
		if (!tradeOverlap && accountHasTrades && !tradeRows.isEmpty()) {
			List<String> positionIds = tradeRows.stream()
					.map(row -> row.symbol() + ":" + row.ticket())
					.distinct()
					.toList();
			tradeOverlap = tradeOrders.findAllByTradingAccountIdAndExternalOrderIdIn(account.getId(), tickets).stream()
							.anyMatch(order -> order.getTradeId() != null)
					|| tradeOrders.findAllByTradingAccountIdAndPositionIdIn(account.getId(), positionIds).stream()
							.anyMatch(order -> order.getTradeId() != null);
		}
		Set<String> fileLogins = rows.stream().map(EightcapReportRow::login)
				.filter(login -> !login.isBlank()).collect(Collectors.toSet());
		boolean loginMismatch = account.getBrokerLogin() != null
				&& fileLogins.stream().anyMatch(login -> !account.getBrokerLogin().equals(login));
		return new Assessment(
				newTrades,
				existingTradesCount,
				newTradeCloses,
				tradeRows.size() - newTradeCloses,
				cashRows.size(),
				newCashTransactions,
				cashRows.size() - newCashTransactions,
				loginMismatch,
				!tradeRows.isEmpty() && accountHasTrades && !tradeOverlap,
				existingTrades,
				existingCloseFingerprints,
				existingCashFingerprints);
	}

	private void validateRows(TradingAccountEntity account, List<EightcapReportRow> rows) {
		Map<String, EightcapReportRow> firstByTicket = new LinkedHashMap<>();
		Set<String> tickets = rows.stream()
				.filter(row -> !row.isCashTransaction())
				.map(EightcapReportRow::ticket)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, TradeEntity> existingTrades = tickets.isEmpty()
				? Map.of()
				: trades.findAllByTradingAccountIdAndTicketIn(account.getId(), tickets).stream()
						.collect(Collectors.toMap(TradeEntity::getTicket, trade -> trade));
		for (EightcapReportRow row : rows) {
			if (!account.getCurrency().equals(row.currency())) {
				throw new EightcapReportException(
						"line " + row.lineNumber() + ": currency does not match the selected trading account.");
			}
			if (row.isCashTransaction()) {
				continue;
			}
			EightcapReportRow first = firstByTicket.putIfAbsent(row.ticket(), row);
			if (first != null && (!first.type().equals(row.type())
					|| !first.symbol().equals(row.symbol())
					|| !first.openTime().equals(row.openTime())
					|| first.openPrice().compareTo(row.openPrice()) != 0)) {
				throw new EightcapReportException(
						"line " + row.lineNumber() + ": rows for the same Ticket have inconsistent trade details.");
			}
			TradeEntity existing = existingTrades.get(row.ticket());
			if (existing != null && (!existing.getSymbol().equals(row.symbol())
					|| !existing.getSide().equals(row.side())
					|| (existing.isHasTradesReport()
							&& (!existing.getOpenTimeServer().equals(row.openTime())
									|| existing.getOpenPrice().compareTo(row.openPrice()) != 0)))) {
				throw new EightcapReportException(
						"line " + row.lineNumber() + ": Ticket conflicts with existing trade details in this account.");
			}
		}
	}

	private List<EightcapReportRow> deduplicate(List<EightcapReportRow> rows) {
		Map<String, EightcapReportRow> unique = new LinkedHashMap<>();
		for (EightcapReportRow row : rows) {
			unique.putIfAbsent(row.fingerprint(), row);
		}
		return new ArrayList<>(unique.values());
	}

	private List<ImportWarning> warnings(
			int totalRows, int duplicateRows, boolean loginMismatch, boolean noExistingTradeOverlap) {
		List<ImportWarning> warnings = new ArrayList<>();
		if ((long) duplicateRows * 10 > totalRows) {
			warnings.add(new ImportWarning(
					"HIGH_DUPLICATE_RATE",
					"Many identical rows were removed. Long-range Eightcap exports may contain duplicates; monthly exports are recommended."));
		}
		if (loginMismatch) {
			warnings.add(new ImportWarning(
					"LOGIN_MISMATCH",
					"The report login does not match this trading account. Confirm only if this report belongs here."));
		}
		if (noExistingTradeOverlap) {
			warnings.add(new ImportWarning(
					"NO_EXISTING_TRADE_OVERLAP",
					"None of the report's trades match existing trades in this account by order ID or position ID."));
		}
		return List.copyOf(warnings);
	}

	private TradingAccountEntity requireOwnedAccount(Long accountId, Long userId) {
		return accounts.findByIdAndUserId(accountId, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trading account was not found."));
	}

	private Instant toUtc(java.time.LocalDateTime serverTime, Short offsetMinutes) {
		if (serverTime == null || offsetMinutes == null) {
			return null;
		}
		return serverTime.minusMinutes(offsetMinutes).toInstant(ZoneOffset.UTC);
	}

	private record Assessment(
			int newTrades,
			int existingTrades,
			int newTradeCloses,
			int alreadyImportedTradeCloses,
			int nonTradeRows,
			int newCashTransactions,
			int alreadyImportedCashTransactions,
			boolean loginMismatch,
			boolean noExistingTradeOverlap,
			Map<String, TradeEntity> existingTradeByTicket,
			Set<String> existingCloseFingerprints,
			Set<String> existingCashFingerprints) {
	}

	public record ImportWarning(String code, String message) {
	}

	public record BatchFile(String filename, byte[] content) {
	}

	public record BatchFileInfo(String filename, String fileSha256, int totalRows) {
	}

	public record MultiFilePreview(List<BatchFileInfo> files, ImportPreview preview) {
	}

	public record MultiFileImportResult(List<BatchFileInfo> files, ImportResult result) {
	}

	private record ComposedBatch(
			String filename,
			byte[] content,
			String fileSha256,
			List<BatchFileInfo> files) {
	}

	public record ImportPreview(
			Long tradingAccountId,
			String filename,
			String fileSha256,
			int totalRows,
			int duplicateRows,
			int uniqueRows,
			int newTrades,
			int existingTrades,
			int newTradeCloses,
			int alreadyImportedTradeCloses,
			int nonTradeRows,
			int newCashTransactions,
			int alreadyImportedCashTransactions,
			boolean loginMismatch,
			int cashRows,
			int errorRows,
			List<ImportWarning> warnings) {
	}

	public record ImportResult(
			Long importBatchId,
			int totalRows,
			int duplicateRows,
			int importedTradeCloses,
			int importedCashTransactions,
			int existingTrades,
			int alreadyImportedTradeCloses,
			int alreadyImportedCashTransactions,
			int nonTradeRows,
			List<ImportWarning> warnings) {
	}

}
