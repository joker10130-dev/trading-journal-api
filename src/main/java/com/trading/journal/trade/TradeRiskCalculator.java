package com.trading.journal.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import com.trading.journal.account.TradingAccountEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeRiskCalculator {

	private static final int R_SCALE = 8;

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
		List<TradeEntity> accountTrades = trades.findAllByTradingAccountId(account.getId());
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
		Map<String, BigDecimal> unitValueBySymbol = inferUnitValues(accountTrades, closesByTrade);
		Map<Long, StopObservation> stopsByTrade = accountTrades.stream()
				.collect(Collectors.toMap(
						TradeEntity::getId,
						trade -> observeStop(trade, ordersByTrade.getOrDefault(trade.getId(), List.of()))));
		Map<String, BigDecimal> medianStopDistanceBySymbol = medianStopDistances(accountTrades, stopsByTrade);

		for (TradeEntity trade : accountTrades) {
			List<TradeCloseEntity> closes = closesByTrade.getOrDefault(trade.getId(), List.of());
			StopObservation stop = stopsByTrade.get(trade.getId());
			BigDecimal medianStopDistance = medianStopDistanceBySymbol.get(trade.getSymbol());
			boolean stopMayHaveMoved = stop.changedPrice()
					|| stop.profitableSide()
					|| (medianStopDistance != null
							&& stop.distance() != null
							&& stop.distance().multiply(BigDecimal.valueOf(2)).compareTo(medianStopDistance) < 0);
			BigDecimal riskAmount = existingManualRisk(trade);
			String riskSource = riskAmount == null ? "NONE" : "MANUAL";
			String riskFlag = stopMayHaveMoved ? "SL_MAY_HAVE_MOVED" : null;

			if (riskAmount == null && !stopMayHaveMoved && stop.initialPrice() != null) {
				BigDecimal unitValue = unitValueBySymbol.get(trade.getSymbol());
				riskAmount = unitValue == null || trade.getOpenPrice() == null || !positive(trade.getOpenedVolume())
						? null
						: stop.initialPrice().subtract(trade.getOpenPrice()).abs()
								.multiply(unitValue)
								.multiply(trade.getOpenedVolume());
				if (positive(riskAmount)) {
					riskSource = "SL_ORDER";
				}
				else {
					riskAmount = null;
				}
			}

			if (riskAmount == null) {
				riskAmount = accountRuleRisk(account, trade, unitValueBySymbol);
				if (positive(riskAmount)) {
					riskSource = "ACCOUNT_RULE";
				}
				else {
					riskAmount = null;
				}
			}
			if (positive(riskAmount)) {
				riskAmount = riskAmount.setScale(5, RoundingMode.HALF_UP);
			}
			if (riskAmount == null && riskFlag == null) {
				riskFlag = "RISK_UNAVAILABLE";
			}

			BigDecimal totalR = null;
			if (positive(riskAmount)) {
				totalR = BigDecimal.ZERO;
				for (TradeCloseEntity close : closes) {
					BigDecimal closeR = close.getNetPnl().divide(riskAmount, R_SCALE, RoundingMode.HALF_UP);
					close.updateRMultiple(closeR);
					totalR = totalR.add(closeR);
				}
			}
			else {
				for (TradeCloseEntity close : closes) {
					close.updateRMultiple(null);
				}
			}
			trade.updateRisk(stop.initialPrice(), riskAmount, riskSource, totalR, riskFlag);
		}
	}

	private BigDecimal existingManualRisk(TradeEntity trade) {
		return "MANUAL".equals(trade.getRiskSource()) ? trade.getRiskAmount() : null;
	}

	private StopObservation observeStop(TradeEntity trade, List<TradeOrderEntity> orders) {
		List<TradeOrderEntity> stopOrders = orders.stream()
				.filter(order -> order.getOrderType() != null
						&& order.getOrderType().toUpperCase(java.util.Locale.ROOT).contains("STOP"))
				.filter(order -> order.getStopPrice() != null && positive(order.getStopPrice()))
				.sorted(Comparator.comparing(
								TradeOrderEntity::getUpdateTimeServer,
								Comparator.nullsLast(Comparator.naturalOrder()))
						.thenComparing(TradeOrderEntity::getExternalOrderId))
				.toList();
		if (stopOrders.isEmpty()) {
			return new StopObservation(null, null, false, false);
		}

		BigDecimal initialPrice = stopOrders.getFirst().getStopPrice();
		boolean changedPrice = stopOrders.stream()
				.map(TradeOrderEntity::getStopPrice)
				.anyMatch(price -> price.compareTo(initialPrice) != 0);
		boolean profitableSide = trade.getOpenPrice() != null
				&& ("LONG".equals(trade.getSide())
						? initialPrice.compareTo(trade.getOpenPrice()) >= 0
						: initialPrice.compareTo(trade.getOpenPrice()) <= 0);
		BigDecimal distance = trade.getOpenPrice() == null
				? null
				: initialPrice.subtract(trade.getOpenPrice()).abs();
		return new StopObservation(initialPrice, distance, changedPrice, profitableSide);
	}

	private BigDecimal accountRuleRisk(
			TradingAccountEntity account,
			TradeEntity trade,
			Map<String, BigDecimal> unitValueBySymbol) {
		BigDecimal value = account.getDefaultRiskValue();
		if (value == null || !positive(value)) {
			return null;
		}
		if ("FIXED_AMOUNT".equals(account.getDefaultRiskMode())) {
			return value;
		}
		if ("FIXED_STOP_DISTANCE".equals(account.getDefaultRiskMode())) {
			BigDecimal unitValue = unitValueBySymbol.get(trade.getSymbol());
			if (unitValue != null && positive(trade.getOpenedVolume())) {
				return value.multiply(unitValue).multiply(trade.getOpenedVolume());
			}
		}
		return null;
	}

	private Map<String, BigDecimal> inferUnitValues(
			List<TradeEntity> trades,
			Map<Long, List<TradeCloseEntity>> closesByTrade) {
		Map<String, List<BigDecimal>> valuesBySymbol = new java.util.HashMap<>();
		for (TradeEntity trade : trades) {
			if (trade.getOpenPrice() == null) {
				continue;
			}
			for (TradeCloseEntity close : closesByTrade.getOrDefault(trade.getId(), List.of())) {
				BigDecimal movement = close.getClosePrice().subtract(trade.getOpenPrice()).abs();
				if (movement.signum() == 0 || !positive(close.getVolume()) || close.getGrossPnl().signum() == 0) {
					continue;
				}
				valuesBySymbol.computeIfAbsent(trade.getSymbol(), ignored -> new ArrayList<>())
						.add(close.getGrossPnl().abs()
								.divide(movement.multiply(close.getVolume()), 12, RoundingMode.HALF_UP));
			}
		}
		Map<String, BigDecimal> medians = new java.util.HashMap<>();
		valuesBySymbol.forEach((symbol, values) -> medians.put(symbol, median(values)));
		return medians;
	}

	private Map<String, BigDecimal> medianStopDistances(
			List<TradeEntity> trades,
			Map<Long, StopObservation> stopsByTrade) {
		Map<String, List<BigDecimal>> distancesBySymbol = new java.util.HashMap<>();
		for (TradeEntity trade : trades) {
			StopObservation stop = stopsByTrade.get(trade.getId());
			if (stop != null
					&& stop.distance() != null
					&& !stop.changedPrice()
					&& !stop.profitableSide()) {
				distancesBySymbol.computeIfAbsent(trade.getSymbol(), ignored -> new ArrayList<>())
						.add(stop.distance());
			}
		}
		Map<String, BigDecimal> medians = new java.util.HashMap<>();
		distancesBySymbol.forEach((symbol, distances) -> medians.put(symbol, median(distances)));
		return medians;
	}

	private BigDecimal median(List<BigDecimal> values) {
		values.sort(Comparator.naturalOrder());
		int middle = values.size() / 2;
		if (values.size() % 2 == 1) {
			return values.get(middle);
		}
		return values.get(middle - 1).add(values.get(middle)).divide(BigDecimal.valueOf(2), 12, RoundingMode.HALF_UP);
	}

	private boolean positive(BigDecimal value) {
		return value != null && value.signum() > 0;
	}

	private record StopObservation(
			BigDecimal initialPrice,
			BigDecimal distance,
			boolean changedPrice,
			boolean profitableSide) {
	}

}
