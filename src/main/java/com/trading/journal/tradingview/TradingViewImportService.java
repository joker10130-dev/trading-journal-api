package com.trading.journal.tradingview;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.trading.journal.account.CurrentUserService;
import com.trading.journal.account.TradingAccountEntity;
import com.trading.journal.account.TradingAccountRepository;
import com.trading.journal.account.UserEntity;
import com.trading.journal.imports.ImportBatchEntity;
import com.trading.journal.imports.ImportBatchRepository;
import com.trading.journal.trade.TradeCloseEntity;
import com.trading.journal.trade.TradeCloseRepository;
import com.trading.journal.trade.TradeEntity;
import com.trading.journal.trade.TradeOrderEntity;
import com.trading.journal.trade.TradeOrderRepository;
import com.trading.journal.trade.TradeRepository;
import com.trading.journal.trade.TradeRiskCalculator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TradingViewImportService {

	private static final short TRADINGVIEW_UTC_OFFSET_MINUTES = 7 * 60;

	private final CurrentUserService currentUserService;
	private final TradingAccountRepository accounts;
	private final ImportBatchRepository importBatches;
	private final TradeRepository trades;
	private final TradeCloseRepository tradeCloses;
	private final TradeOrderRepository tradeOrders;
	private final TradeRiskCalculator tradeRiskCalculator;
	private final TradingViewReportReader reader;

	public TradingViewImportService(
			CurrentUserService currentUserService,
			TradingAccountRepository accounts,
			ImportBatchRepository importBatches,
			TradeRepository trades,
			TradeCloseRepository tradeCloses,
			TradeOrderRepository tradeOrders,
			TradeRiskCalculator tradeRiskCalculator,
			TradingViewReportReader reader) {
		this.currentUserService = currentUserService;
		this.accounts = accounts;
		this.importBatches = importBatches;
		this.trades = trades;
		this.tradeCloses = tradeCloses;
		this.tradeOrders = tradeOrders;
		this.tradeRiskCalculator = tradeRiskCalculator;
		this.reader = reader;
	}

	@Transactional(readOnly = true)
	public ImportPreview preview(Long accountId, String filename, byte[] content) {
		UserEntity user = currentUserService.requireCurrentUser();
		TradingAccountEntity account = requireOwnedAccount(accountId, user.getId());
		TradingViewReportReader.ParsedTradingViewReport report = reader.read(filename, content);
		List<TradingViewOrder> uniqueOrders = deduplicate(report.orders());
		Map<String, TradeOrderEntity> importedOrders = findAndValidateExistingOrders(account, uniqueOrders);
		Map<String, TradeEntity> existingTrades = findTrades(account, uniqueOrders);
		Set<String> newPositionTickets = newPositionTickets(uniqueOrders, existingTrades);
		int newOrders = uniqueOrders.size() - importedOrders.size();
		return new ImportPreview(
				account.getId(),
				report.filename(),
				report.fileSha256(),
				report.orders().size(),
				report.orders().size() - uniqueOrders.size(),
				uniqueOrders.size(),
				newOrders,
				importedOrders.size(),
				newPositionTickets.size(),
				existingTrades.size(),
				(int) uniqueOrders.stream().filter(order -> !order.isFilled()).count());
	}

	@Transactional
	public ImportResult importReport(Long accountId, String filename, byte[] content, String expectedSha256) {
		UserEntity user = currentUserService.requireCurrentUser();
		TradingAccountEntity account = accounts.findByIdAndUserIdForUpdate(accountId, user.getId())
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "Trading account was not found."));
		TradingViewReportReader.ParsedTradingViewReport report = reader.read(filename, content);
		if (expectedSha256 == null
				|| !MessageDigest.isEqual(
						report.fileSha256().getBytes(StandardCharsets.US_ASCII),
						expectedSha256.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
			throw new ResponseStatusException(
					HttpStatus.CONFLICT, "The uploaded file changed after preview. Preview it again before importing.");
		}

		List<TradingViewOrder> uniqueOrders = deduplicate(report.orders());
		Map<String, TradeOrderEntity> importedOrders = findAndValidateExistingOrders(account, uniqueOrders);
		Map<String, TradeEntity> tradeByTicket = new HashMap<>(findTrades(account, uniqueOrders));
		int newOrderCount = uniqueOrders.size() - importedOrders.size();
		int newTradeCount = newPositionTickets(uniqueOrders, tradeByTicket).size();
		int nonTradeRows = (int) uniqueOrders.stream().filter(order -> !order.isFilled()).count();
		ImportBatchEntity batch = importBatches.saveAndFlush(new ImportBatchEntity(
				user.getId(),
				account.getId(),
				"TRADINGVIEW",
				report.filename(),
				report.fileSha256(),
				report.orders().size(),
				report.orders().size() - uniqueOrders.size(),
				newOrderCount,
				nonTradeRows));

		List<TradingViewOrder> newOrders = uniqueOrders.stream()
				.filter(order -> !importedOrders.containsKey(order.orderId()))
				.toList();
		for (TradingViewOrder order : newOrders) {
			tradeOrders.save(toEntity(user.getId(), account.getId(), batch.getId(), order));
		}
		if (!newOrders.isEmpty()) {
			tradeOrders.flush();
		}

		Map<String, String> positionIdByTicket = positionIdByTicket(uniqueOrders);
		List<String> positionIds = positionIdByTicket.values().stream().distinct().toList();
		List<TradeOrderEntity> allPositionOrders = positionIds.isEmpty()
				? List.of()
				: tradeOrders.findAllByTradingAccountIdAndPositionIdIn(account.getId(), positionIds);
		Map<String, List<TradeOrderEntity>> ordersByPosition = allPositionOrders.stream()
				.collect(Collectors.groupingBy(TradeOrderEntity::getPositionId));

		for (Map.Entry<String, String> entry : positionIdByTicket.entrySet()) {
			String ticket = entry.getKey();
			String positionId = entry.getValue();
			List<TradeOrderEntity> positionOrders = ordersByPosition.getOrDefault(positionId, List.of());
			TradeEntity trade = tradeByTicket.get(ticket);
			if (trade == null) {
				TradingViewOrder openingOrder = openingOrder(positionOrders, ticket);
				if (openingOrder == null) {
					continue;
				}
				trade = createTradingViewTrade(user.getId(), account.getId(), ticket, openingOrder);
				trade = trades.saveAndFlush(trade);
				tradeByTicket.put(ticket, trade);
			}
			trade.markHasTradingViewData();
			for (TradeOrderEntity order : positionOrders) {
				order.attachToTrade(trade.getId());
			}
			if (!trade.isHasTradesReport()) {
				updateTradingViewTrade(trade, positionOrders, ticket);
				persistTradingViewCloses(user.getId(), account.getId(), batch.getId(), trade, positionOrders);
			}
		}
		tradeRiskCalculator.recalculate(account);

		return new ImportResult(
				batch.getId(),
				report.orders().size(),
				report.orders().size() - uniqueOrders.size(),
				newOrderCount,
				importedOrders.size(),
				newTradeCount,
				nonTradeRows);
	}

	private Map<String, TradeOrderEntity> findAndValidateExistingOrders(
			TradingAccountEntity account,
			List<TradingViewOrder> orders) {
		if (orders.isEmpty()) {
			return Map.of();
		}
		Map<String, TradeOrderEntity> existing = tradeOrders.findAllByTradingAccountIdAndExternalOrderIdIn(
						account.getId(), orders.stream().map(TradingViewOrder::orderId).toList())
				.stream()
				.collect(Collectors.toMap(TradeOrderEntity::getExternalOrderId, order -> order));
		for (TradingViewOrder order : orders) {
			TradeOrderEntity prior = existing.get(order.orderId());
			if (prior != null && !prior.getRawRow().equals(order.rawRow())) {
				throw new ResponseStatusException(
						HttpStatus.CONFLICT,
						"TradingView Order ID " + order.orderId() + " already exists with different row content.");
			}
		}
		return existing;
	}

	private Map<String, TradeEntity> findTrades(TradingAccountEntity account, List<TradingViewOrder> orders) {
		Set<String> tickets = orders.stream()
				.map(TradingViewOrder::ticket)
				.filter(ticket -> ticket != null)
				.collect(Collectors.toSet());
		if (tickets.isEmpty()) {
			return Map.of();
		}
		Map<String, TradeEntity> existingTrades = trades.findAllByTradingAccountIdAndTicketIn(account.getId(), tickets).stream()
				.collect(Collectors.toMap(TradeEntity::getTicket, trade -> trade));
		for (TradingViewOrder order : orders) {
			TradeEntity existing = existingTrades.get(order.ticket());
			if (existing != null && !existing.getSymbol().equals(order.symbol())) {
				throw new TradingViewReportException(
						"Order ID " + order.orderId() + " conflicts with an existing trade symbol.");
			}
		}
		return existingTrades;
	}

	private Set<String> newPositionTickets(
			List<TradingViewOrder> orders,
			Map<String, TradeEntity> existingTrades) {
		Map<String, TradingViewOrder> openings = new LinkedHashMap<>();
		for (TradingViewOrder order : orders) {
			if (order.isFilled() && order.hasPosition() && order.ticket().equals(order.orderId())) {
				openings.putIfAbsent(order.ticket(), order);
			}
		}
		openings.forEach((ticket, opening) -> {
			TradeEntity existing = existingTrades.get(ticket);
			String side = "BUY".equals(opening.side()) ? "LONG" : "SHORT";
			if (existing != null && (!existing.getSymbol().equals(opening.symbol())
					|| !existing.getSide().equals(side))) {
				throw new TradingViewReportException(
						"Order ID " + opening.orderId() + " conflicts with an existing trade.");
			}
		});
		Set<String> newTickets = new HashSet<>(openings.keySet());
		newTickets.removeAll(existingTrades.keySet());
		return newTickets;
	}

	private List<TradingViewOrder> deduplicate(List<TradingViewOrder> orders) {
		Map<String, TradingViewOrder> unique = new LinkedHashMap<>();
		for (TradingViewOrder order : orders) {
			TradingViewOrder prior = unique.putIfAbsent(order.orderId(), order);
			if (prior != null && !prior.rawRow().equals(order.rawRow())) {
				throw new TradingViewReportException(
						"line " + order.lineNumber() + ": Order ID has conflicting duplicate rows.");
			}
		}
		return new ArrayList<>(unique.values());
	}

	private Map<String, String> positionIdByTicket(List<TradingViewOrder> orders) {
		Map<String, String> positionIds = new LinkedHashMap<>();
		for (TradingViewOrder order : orders) {
			if (!order.hasPosition()) {
				continue;
			}
			String ticket = order.ticket();
			String prior = positionIds.putIfAbsent(ticket, order.positionId());
			if (prior != null && !prior.equals(order.positionId())) {
				throw new TradingViewReportException(
						"Order ID " + order.orderId() + " assigns one ticket to multiple positions.");
			}
		}
		return positionIds;
	}

	private TradingViewOrder openingOrder(List<TradeOrderEntity> orders, String ticket) {
		return orders.stream()
				.filter(order -> order.getStatus().equals("FILLED"))
				.filter(order -> order.getExternalOrderId().equals(ticket))
				.map(this::fromEntity)
				.findFirst()
				.orElse(null);
	}

	private TradeEntity createTradingViewTrade(
			Long userId,
			Long accountId,
			String ticket,
			TradingViewOrder openingOrder) {
		if (openingOrder.side() == null || openingOrder.filledQuantity() == null
				|| openingOrder.averageFillPrice() == null || openingOrder.updateTime() == null
				|| openingOrder.filledQuantity().signum() <= 0
				|| openingOrder.averageFillPrice().signum() <= 0) {
			throw new TradingViewReportException(
					"Opening order " + openingOrder.orderId() + " is missing valid fill details.");
		}
		TradeEntity trade = new TradeEntity(
				userId,
				accountId,
				ticket,
				openingOrder.symbol(),
				openingOrder.side().equals("BUY") ? "LONG" : "SHORT",
				openingOrder.updateTime(),
				toUtc(openingOrder.updateTime()),
				openingOrder.averageFillPrice(),
				false,
				true);
		trade.replaceTradingViewData(
				openingOrder.symbol(),
				openingOrder.side().equals("BUY") ? "LONG" : "SHORT",
				"OPEN",
				openingOrder.updateTime(),
				toUtc(openingOrder.updateTime()),
				openingOrder.averageFillPrice(),
				openingOrder.filledQuantity(),
				BigDecimal.ZERO,
				BigDecimal.ZERO,
				BigDecimal.ZERO);
		return trade;
	}

	private void updateTradingViewTrade(TradeEntity trade, List<TradeOrderEntity> orders, String ticket) {
		TradingViewOrder opening = openingOrder(orders, ticket);
		if (opening == null) {
			return;
		}
		BigDecimal closedVolume = BigDecimal.ZERO;
		BigDecimal grossPnl = BigDecimal.ZERO;
		BigDecimal netPnl = BigDecimal.ZERO;
		for (TradeOrderEntity entity : orders) {
			TradingViewOrder order = fromEntity(entity);
			if (isClose(order)) {
				closedVolume = closedVolume.add(order.filledQuantity());
				grossPnl = grossPnl.add(gross(order));
				netPnl = netPnl.add(net(order));
			}
		}
		String status = closedVolume.signum() == 0
				? "OPEN"
				: closedVolume.compareTo(opening.filledQuantity()) >= 0 ? "CLOSED" : "PARTIALLY_CLOSED";
		trade.replaceTradingViewData(
				opening.symbol(),
				opening.side().equals("BUY") ? "LONG" : "SHORT",
				status,
				opening.updateTime(),
				toUtc(opening.updateTime()),
				opening.averageFillPrice(),
				opening.filledQuantity(),
				grossPnl,
				netPnl.subtract(grossPnl),
				netPnl);
	}

	private void persistTradingViewCloses(
			Long userId,
			Long accountId,
			Long batchId,
			TradeEntity trade,
			List<TradeOrderEntity> orders) {
		Set<String> knownFingerprints = tradeCloses
				.findAllByTradingAccountIdAndTradeIdAndSource(accountId, trade.getId(), "TRADINGVIEW").stream()
				.map(TradeCloseEntity::getSourceFingerprint)
				.collect(Collectors.toSet());
		for (TradeOrderEntity entity : orders) {
			TradingViewOrder order = fromEntity(entity);
			if (!isClose(order) || !knownFingerprints.add(order.fingerprint())) {
				continue;
			}
			BigDecimal gross = gross(order);
			BigDecimal net = net(order);
			tradeCloses.save(new TradeCloseEntity(
					userId,
					accountId,
					trade.getId(),
					batchId,
					"TRADINGVIEW",
					order.fingerprint(),
					order.filledQuantity(),
					order.updateTime(),
					toUtc(order.updateTime()),
					order.averageFillPrice(),
					gross,
					BigDecimal.ZERO,
					net.subtract(gross),
					net));
		}
	}

	private boolean isClose(TradingViewOrder order) {
		return order.isFilled()
				&& order.hasClosedPnl()
				&& order.filledQuantity() != null
				&& order.filledQuantity().signum() > 0
				&& order.averageFillPrice() != null
				&& order.averageFillPrice().signum() > 0;
	}

	private BigDecimal gross(TradingViewOrder order) {
		return order.closedPnl() != null ? order.closedPnl() : order.netClosedPnl();
	}

	private BigDecimal net(TradingViewOrder order) {
		return order.netClosedPnl() != null ? order.netClosedPnl() : order.closedPnl();
	}

	private TradeOrderEntity toEntity(
			Long userId,
			Long accountId,
			Long batchId,
			TradingViewOrder order) {
		BigDecimal volume = order.filledQuantity() != null ? order.filledQuantity() : order.quantity();
		return new TradeOrderEntity(
				userId,
				accountId,
				batchId,
				order.orderId(),
				order.positionId().isBlank() ? null : order.positionId(),
				order.symbol(),
				order.side(),
				order.type(),
				order.status(),
				volume,
				order.updateTime(),
				toUtc(order.updateTime()),
				order.limitPrice() != null ? order.limitPrice() : order.stopPrice(),
				order.averageFillPrice(),
				order.stopPrice(),
				order.limitPrice(),
				order.closedPnl(),
				order.netClosedPnl(),
				order.commission(),
				order.rawRow());
	}

	private TradingViewOrder fromEntity(TradeOrderEntity entity) {
		Map<String, String> raw = entity.getRawRow();
		return new TradingViewOrder(
				0,
				entity.getSymbol(),
				entity.getSide(),
				entity.getOrderType(),
				decimal(raw, "Qty"),
				decimal(raw, "Filled Qty"),
				entity.getLimitPrice(),
				entity.getStopPrice(),
				entity.getAverageFillPrice(),
				entity.getStatus(),
				entity.getUpdateTimeServer(),
				entity.getPositionId() == null ? "" : entity.getPositionId(),
				entity.getCommission(),
				entity.getClosedPnl(),
				entity.getNetClosedPnl(),
				entity.getExternalOrderId(),
				raw);
	}

	private BigDecimal decimal(Map<String, String> raw, String column) {
		String value = raw.get(column);
		return value == null || value.isBlank() ? null : new BigDecimal(value);
	}

	private Instant toUtc(java.time.LocalDateTime localTime) {
		return localTime == null
				? null
				: localTime.minusMinutes(TRADINGVIEW_UTC_OFFSET_MINUTES).toInstant(ZoneOffset.UTC);
	}

	private TradingAccountEntity requireOwnedAccount(Long accountId, Long userId) {
		return accounts.findByIdAndUserId(accountId, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trading account was not found."));
	}

	public record ImportPreview(
			Long tradingAccountId,
			String filename,
			String fileSha256,
			int totalRows,
			int duplicateRows,
			int uniqueRows,
			int newOrders,
			int alreadyImportedOrders,
			int newTrades,
			int existingTrades,
			int nonTradeRows) {
	}

	public record ImportResult(
			Long importBatchId,
			int totalRows,
			int duplicateRows,
			int importedOrders,
			int alreadyImportedOrders,
			int newTrades,
			int nonTradeRows) {
	}

}
