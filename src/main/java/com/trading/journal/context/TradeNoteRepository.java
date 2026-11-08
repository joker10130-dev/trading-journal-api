package com.trading.journal.context;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeNoteRepository extends JpaRepository<TradeNoteEntity, Long> {

	Optional<TradeNoteEntity> findByTradeIdAndUserId(Long tradeId, Long userId);

	void deleteByTradeIdAndUserId(Long tradeId, Long userId);
}
