package com.trading.journal.trade;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRepository extends JpaRepository<TradeEntity, Long> {

	Optional<TradeEntity> findByTradingAccountIdAndTicket(Long tradingAccountId, String ticket);

	List<TradeEntity> findAllByTradingAccountIdAndTicketIn(Long tradingAccountId, Collection<String> tickets);

	List<TradeEntity> findAllByTradingAccountId(Long tradingAccountId);

	boolean existsByTradingAccountId(Long tradingAccountId);

}
