package com.trading.journal.trade;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TradeOrderRepository extends JpaRepository<TradeOrderEntity, Long> {

	List<TradeOrderEntity> findAllByTradingAccountIdAndExternalOrderIdIn(
			Long tradingAccountId,
			Collection<String> externalOrderIds);

	List<TradeOrderEntity> findAllByTradingAccountIdAndPositionIdIn(
			Long tradingAccountId,
			Collection<String> positionIds);

	@Query("""
			select o from TradeOrderEntity o
			where o.tradingAccountId = :accountId
			  and o.stopPrice is not null and o.stopPrice > 0
			  and upper(o.orderType) like '%STOP%'
			""")
	List<TradeOrderEntity> findStopOrders(@Param("accountId") Long accountId);

	List<TradeOrderEntity> findAllByTradingAccountIdAndTradeIdIn(
			Long tradingAccountId,
			Collection<Long> tradeIds);

	List<TradeOrderEntity> findAllByImportBatchId(Long importBatchId);

}
