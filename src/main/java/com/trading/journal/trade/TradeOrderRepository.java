package com.trading.journal.trade;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeOrderRepository extends JpaRepository<TradeOrderEntity, Long> {

	List<TradeOrderEntity> findAllByTradingAccountIdAndExternalOrderIdIn(
			Long tradingAccountId,
			Collection<String> externalOrderIds);

	List<TradeOrderEntity> findAllByTradingAccountIdAndPositionIdIn(
			Long tradingAccountId,
			Collection<String> positionIds);

	List<TradeOrderEntity> findAllByTradingAccountIdAndTradeIdIn(
			Long tradingAccountId,
			Collection<Long> tradeIds);

}
