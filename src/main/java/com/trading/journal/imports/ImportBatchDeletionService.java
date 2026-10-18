package com.trading.journal.imports;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.trading.journal.account.AccountTransactionEntity;
import com.trading.journal.account.AccountTransactionRepository;
import com.trading.journal.account.TradingAccountEntity;
import com.trading.journal.account.TradingAccountRepository;
import com.trading.journal.trade.TradeCloseEntity;
import com.trading.journal.trade.TradeCloseRepository;
import com.trading.journal.trade.TradeEntity;
import com.trading.journal.trade.TradeManualRService;
import com.trading.journal.trade.TradeOrderEntity;
import com.trading.journal.trade.TradeOrderRepository;
import com.trading.journal.trade.TradeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImportBatchDeletionService {

	private final TradingAccountRepository accounts;
	private final ImportBatchRepository batches;
	private final TradeRepository trades;
	private final TradeCloseRepository closes;
	private final TradeOrderRepository orders;
	private final AccountTransactionRepository cashTransactions;
	private final TradeManualRService manualR;

	public ImportBatchDeletionService(
			TradingAccountRepository accounts,
			ImportBatchRepository batches,
			TradeRepository trades,
			TradeCloseRepository closes,
			TradeOrderRepository orders,
			AccountTransactionRepository cashTransactions,
			TradeManualRService manualR) {
		this.accounts = accounts;
		this.batches = batches;
		this.trades = trades;
		this.closes = closes;
		this.orders = orders;
		this.cashTransactions = cashTransactions;
		this.manualR = manualR;
	}

	@Transactional
	public void delete(Long accountId, Long batchId, Long userId) {
		TradingAccountEntity account = accounts.findByIdAndUserIdForUpdate(accountId, userId)
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "Trading account was not found."));
		ImportBatchEntity batch = batches.findByIdAndTradingAccountIdAndUserId(
						batchId, account.getId(), userId)
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "Import batch was not found."));

		List<TradeCloseEntity> batchCloses = closes.findAllByImportBatchId(batch.getId());
		List<TradeOrderEntity> batchOrders = orders.findAllByImportBatchId(batch.getId());
		List<AccountTransactionEntity> batchCash = cashTransactions.findAllByImportBatchId(batch.getId());
		Set<Long> affectedTradeIds = new HashSet<>();
		batchCloses.stream().map(TradeCloseEntity::getTradeId).forEach(affectedTradeIds::add);
		batchOrders.stream().map(TradeOrderEntity::getTradeId).filter(id -> id != null)
				.forEach(affectedTradeIds::add);

		closes.deleteAll(batchCloses);
		orders.deleteAll(batchOrders);
		cashTransactions.deleteAll(batchCash);
		closes.flush();
		orders.flush();
		cashTransactions.flush();
		for (Long tradeId : affectedTradeIds) {
			trades.findByIdAndUserId(tradeId, userId).ifPresent(trade -> rebuildOrDelete(trade, accountId));
		}
		batches.delete(batch);
	}

	private void rebuildOrDelete(TradeEntity trade, Long accountId) {
		List<TradeCloseEntity> remainingCloses =
				closes.findAllByTradingAccountIdAndTradeId(accountId, trade.getId());
		List<TradeOrderEntity> remainingOrders =
				orders.findAllByTradingAccountIdAndTradeIdIn(accountId, List.of(trade.getId()));
		List<TradeCloseEntity> reportCloses = remainingCloses.stream()
				.filter(close -> "TRADES_REPORT".equals(close.getSource()))
				.toList();
		List<TradeCloseEntity> tradingViewCloses = remainingCloses.stream()
				.filter(close -> "TRADINGVIEW".equals(close.getSource()))
				.toList();
		List<TradeCloseEntity> manualCloses = remainingCloses.stream()
				.filter(close -> "MANUAL".equals(close.getSource()))
				.toList();

		if (!reportCloses.isEmpty()) {
			rebuildFromCloses(
					trade, reportCloses, "CLOSED", sumVolume(reportCloses), true, !remainingOrders.isEmpty());
		} else if (!remainingOrders.isEmpty()) {
			TradeOrderEntity opening = remainingOrders.stream()
					.filter(order -> "FILLED".equalsIgnoreCase(order.getStatus()))
					.filter(order -> order.getExternalOrderId().equals(trade.getTicket()))
					.filter(order -> order.getVolume() != null && order.getVolume().signum() > 0)
					.filter(order -> order.getAverageFillPrice() != null
							&& order.getAverageFillPrice().signum() > 0)
					.findFirst()
					.orElse(null);
			if (opening == null) {
				closes.deleteAll(remainingCloses);
				orders.deleteAll(remainingOrders);
				trades.delete(trade);
				return;
			}
			BigDecimal closedVolume = sumVolume(tradingViewCloses);
			String status = closedVolume.signum() == 0
					? "OPEN"
					: closedVolume.compareTo(opening.getVolume()) >= 0 ? "CLOSED" : "PARTIALLY_CLOSED";
			rebuildFromCloses(
					trade,
					tradingViewCloses,
					status,
					opening.getVolume(),
					false,
					true,
					opening);
		} else if (!manualCloses.isEmpty()) {
			rebuildFromCloses(
					trade, manualCloses, "CLOSED", sumVolume(manualCloses), false, false);
		} else {
			closes.deleteAll(remainingCloses);
			trades.delete(trade);
			return;
		}
		manualR.redistributeManualR(trade);
		trades.save(trade);
	}

	private void rebuildFromCloses(
			TradeEntity trade,
			List<TradeCloseEntity> sourceCloses,
			String status,
			BigDecimal openedVolume,
			boolean hasTradesReport,
			boolean hasTradingViewData) {
		rebuildFromCloses(
				trade,
				sourceCloses,
				status,
				openedVolume,
				hasTradesReport,
				hasTradingViewData,
				null);
	}

	private void rebuildFromCloses(
			TradeEntity trade,
			List<TradeCloseEntity> sourceCloses,
			String status,
			BigDecimal openedVolume,
			boolean hasTradesReport,
			boolean hasTradingViewData,
			TradeOrderEntity opening) {
		BigDecimal grossPnl = sourceCloses.stream()
				.map(TradeCloseEntity::getGrossPnl)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal feesAndSwap = sourceCloses.stream()
				.map(close -> close.getSwaps().add(close.getCommission()))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal netPnl = sourceCloses.stream()
				.map(TradeCloseEntity::getNetPnl)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		trade.rebuildImportedSummary(
				opening == null ? trade.getSymbol() : opening.getSymbol(),
				opening == null ? trade.getSide() : "BUY".equalsIgnoreCase(opening.getSide()) ? "LONG" : "SHORT",
				status,
				opening == null ? trade.getOpenTimeServer() : opening.getUpdateTimeServer(),
				opening == null ? trade.getOpenedAt() : opening.getUpdatedAt(),
				opening == null ? trade.getOpenPrice() : opening.getAverageFillPrice(),
				openedVolume,
				grossPnl,
				feesAndSwap,
				netPnl,
				hasTradesReport,
				hasTradingViewData);
	}

	private BigDecimal sumVolume(List<TradeCloseEntity> sourceCloses) {
		return sourceCloses.stream()
				.map(TradeCloseEntity::getVolume)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

}
