package com.trading.journal.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TradeManualRService {

	private static final int R_SCALE = 8;

	private final TradeRepository trades;
	private final TradeCloseRepository tradeCloses;

	public TradeManualRService(TradeRepository trades, TradeCloseRepository tradeCloses) {
		this.trades = trades;
		this.tradeCloses = tradeCloses;
	}

	@Transactional
	public TradeEntity setManualR(Long tradeId, Long userId, BigDecimal rMultiple) {
		TradeEntity trade = trades.findByIdAndUserId(tradeId, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trade was not found."));
		List<TradeCloseEntity> closes =
				tradeCloses.findAllByTradingAccountIdAndTradeId(trade.getTradingAccountId(), trade.getId());
		if (rMultiple == null) {
			trade.clearManualR();
			closes.forEach(close -> close.updateRMultiple(null));
		} else {
			BigDecimal r = rMultiple.setScale(R_SCALE, RoundingMode.HALF_UP);
			trade.applyManualR(r);
			distribute(r, closes);
		}
		return trades.save(trade);
	}

	@Transactional
	public void redistributeManualR(TradeEntity trade) {
		if ("MANUAL".equals(trade.getRSource()) && trade.getRMultiple() != null) {
			distribute(
					trade.getRMultiple(),
					tradeCloses.findAllByTradingAccountIdAndTradeId(
							trade.getTradingAccountId(), trade.getId()));
		}
	}

	// Closes feed the performance summaries, so the manual R is split by volume; the last close takes the remainder.
	private void distribute(BigDecimal r, List<TradeCloseEntity> closes) {
		List<TradeCloseEntity> ordered = closes.stream().sorted(Comparator.comparing(TradeCloseEntity::getId)).toList();
		BigDecimal totalVolume = ordered.stream().map(TradeCloseEntity::getVolume).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal assigned = BigDecimal.ZERO;
		for (int i = 0; i < ordered.size(); i++) {
			TradeCloseEntity close = ordered.get(i);
			BigDecimal share = i == ordered.size() - 1
					? r.subtract(assigned)
					: r.multiply(close.getVolume()).divide(totalVolume, R_SCALE, RoundingMode.HALF_UP);
			close.updateRMultiple(share);
			assigned = assigned.add(share);
		}
	}

}