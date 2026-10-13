package com.trading.journal.trade;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.trading.journal.account.TradingAccountEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeRiskCalculator {

	static final String NO_INITIAL_STOP = "no initial stop";
	static final String UNKNOWN_CONTRACT_SIZE = "unknown contract size";
	static final String TICKET_NOT_AN_ORDER_ID = "ticket not an order id";

	private static final int R_SCALE = 8;
	private static final Pattern DIGITS = Pattern.compile("\\d+");

	private final TradeRepository trades;
	private final TradeCloseRepository tradeCloses;
	private final TradeOrderRepository tradeOrders;

	public TradeRiskCalculator(
			TradeRepository trades,
			TradeCloseRepository tradeCloses,
			TradeOrderRepository tradeOrders) {
		this.trades = trades;
		this.tradeCloses = tradeCloses;
		this.tradeOrders = tradeOrders;
	}

	@Transactional
	public void recalculate(TradingAccountEntity account) {
		List<TradeEntity> accountTrades = trades.findAllByTradingAccountId(account.getId()).stream()
				.filter(trade -> !"MANUAL".equals(trade.getRSource()))
				.toList();
		if (accountTrades.isEmpty()) {
			return;
		}
		List<Long> tradeIds = accountTrades.stream().map(TradeEntity::getId).filter(Objects::nonNull).toList();
		Map<Long, List<TradeCloseEntity>> closesByTrade = tradeCloses
				.findAllByTradingAccountIdAndTradeIdIn(account.getId(), tradeIds).stream()
				.collect(Collectors.groupingBy(TradeCloseEntity::getTradeId));
		Map<Long, List<TradeOrderEntity>> ordersByTrade = tradeOrders
				.findAllByTradingAccountIdAndTradeIdIn(account.getId(), tradeIds).stream()
				.collect(Collectors.groupingBy(TradeOrderEntity::getTradeId));
		Map<String, List<TradeOrderEntity>> stopsBySymbol = tradeOrders.findStopOrders(account.getId()).stream()
				.filter(order -> order.getSymbol() != null && order.getSide() != null)
				.filter(order -> DIGITS.matcher(order.getExternalOrderId()).matches())
				.collect(Collectors.groupingBy(order -> order.getSymbol().toUpperCase(Locale.ROOT)));

		for (TradeEntity trade : accountTrades) {
			List<TradeCloseEntity> closes = closesByTrade.getOrDefault(trade.getId(), List.of());
			if (!trade.isHasTradingViewData() || !"CLOSED".equals(trade.getStatus())) {
				clear(trade, closes, null, null);
				continue;
			}
			if (!DIGITS.matcher(trade.getTicket()).matches()) {
				clear(trade, closes, null, TICKET_NOT_AN_ORDER_ID);
				continue;
			}
			Optional<BigInteger> firstCloseOrderId = firstCloseOrderId(
					ordersByTrade.getOrDefault(trade.getId(), List.of()));
			if (firstCloseOrderId.isEmpty()) {
				clear(trade, closes, null, NO_INITIAL_STOP);
				continue;
			}
			Optional<TradeOrderEntity> stop = initialStop(
					trade,
					stopsBySymbol.getOrDefault(trade.getSymbol().toUpperCase(Locale.ROOT), List.of()),
					firstCloseOrderId.get());
			if (stop.isEmpty()) {
				clear(trade, closes, null, NO_INITIAL_STOP);
				continue;
			}
			BigDecimal stopPrice = stop.get().getStopPrice();
			Optional<BigDecimal> contractSize = ContractSizes.forSymbol(trade.getSymbol());
			if (contractSize.isEmpty()) {
				clear(trade, closes, stopPrice, UNKNOWN_CONTRACT_SIZE);
				continue;
			}
			BigDecimal risk = trade.getOpenPrice().subtract(stopPrice).abs()
					.multiply(trade.getOpenedVolume())
					.multiply(contractSize.get());
			if (risk.signum() <= 0) {
				clear(trade, closes, stopPrice, NO_INITIAL_STOP);
				continue;
			}
			for (TradeCloseEntity close : closes) {
				close.updateRMultiple(close.getNetPnl().divide(risk, R_SCALE, RoundingMode.HALF_UP));
			}
			trade.updateRisk(
					stopPrice,
					risk.setScale(5, RoundingMode.HALF_UP),
					"SL_ORDER",
					trade.getNetPnl().divide(risk, R_SCALE, RoundingMode.HALF_UP),
					null,
					"AUTO");
		}
	}

	private Optional<BigInteger> firstCloseOrderId(List<TradeOrderEntity> orders) {
		return orders.stream()
				.filter(order -> "FILLED".equalsIgnoreCase(order.getStatus()))
				.filter(order -> order.getClosedPnl() != null || order.getNetClosedPnl() != null)
				.filter(order -> order.getVolume() != null && order.getVolume().signum() > 0)
				.filter(order -> order.getAverageFillPrice() != null && order.getAverageFillPrice().signum() > 0)
				.filter(order -> DIGITS.matcher(order.getExternalOrderId()).matches())
				.map(order -> new BigInteger(order.getExternalOrderId()))
				.min(Comparator.naturalOrder());
	}

	private Optional<TradeOrderEntity> initialStop(
			TradeEntity trade,
			List<TradeOrderEntity> stops,
			BigInteger firstCloseOrderId) {
		BigInteger entryOrderId = new BigInteger(trade.getTicket());
		String stopSide = "LONG".equalsIgnoreCase(trade.getSide()) ? "SELL" : "BUY";
		return stops.stream()
				.filter(order -> order.getOrderType() != null
						&& order.getOrderType().toUpperCase(Locale.ROOT).contains("STOP"))
				.filter(order -> stopSide.equalsIgnoreCase(order.getSide()))
				.filter(order -> {
					BigInteger orderId = new BigInteger(order.getExternalOrderId());
					return orderId.compareTo(entryOrderId) > 0 && orderId.compareTo(firstCloseOrderId) < 0;
				})
				.min(Comparator.comparing(order -> new BigInteger(order.getExternalOrderId())));
	}

	private void clear(TradeEntity trade, List<TradeCloseEntity> closes, BigDecimal stopPrice, String flag) {
		for (TradeCloseEntity close : closes) {
			close.updateRMultiple(null);
		}
		trade.updateRisk(stopPrice, null, "NONE", null, flag, "NONE");
	}

}