package com.trading.journal.trade;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeCloseRepository extends JpaRepository<TradeCloseEntity, Long> {

	List<TradeCloseEntity> findAllByTradingAccountIdAndTradeIdIn(
			Long tradingAccountId,
			Collection<Long> tradeIds);

	List<TradeCloseEntity> findAllByTradingAccountIdAndTradeId(
			Long tradingAccountId,
			Long tradeId);

	List<TradeCloseEntity> findAllByTradingAccountIdAndTradeIdAndSource(
			Long tradingAccountId,
			Long tradeId,
			String source);

}
