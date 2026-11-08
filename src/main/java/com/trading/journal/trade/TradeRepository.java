package com.trading.journal.trade;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRepository extends JpaRepository<TradeEntity, Long> {

	Optional<TradeEntity> findByTradingAccountIdAndTicket(Long tradingAccountId, String ticket);

	List<TradeEntity> findAllByTradingAccountIdAndTicketIn(Long tradingAccountId, Collection<String> tickets);

	Optional<TradeEntity> findByIdAndUserId(Long id, Long userId);

	Optional<TradeEntity> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

	Optional<TradeEntity> findByIdAndTradingAccountIdAndUserIdAndDeletedAtIsNull(
			Long id,
			Long tradingAccountId,
			Long userId);

	List<TradeEntity> findAllByTradingAccountId(Long tradingAccountId);

	List<TradeEntity> findAllByTradingAccountIdAndTicketIsNullAndDeletedAtIsNull(Long tradingAccountId);

	boolean existsByTradingAccountIdAndTicketAndIdNot(Long tradingAccountId, String ticket, Long id);

	boolean existsByTradingAccountId(Long tradingAccountId);

}
