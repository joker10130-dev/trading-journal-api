package com.trading.journal.context;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeImageRepository extends JpaRepository<TradeImageEntity, Long> {

	List<TradeImageEntity> findAllByTradeIdAndUserIdOrderByCreatedAt(Long tradeId, Long userId);

	Optional<TradeImageEntity> findByIdAndTradeIdAndUserId(Long id, Long tradeId, Long userId);

	void deleteByIdAndTradeIdAndUserId(Long id, Long tradeId, Long userId);

	void deleteAllByTradeIdAndUserId(Long tradeId, Long userId);
}
