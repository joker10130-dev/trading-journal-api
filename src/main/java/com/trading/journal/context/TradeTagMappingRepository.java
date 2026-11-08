package com.trading.journal.context;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeTagMappingRepository extends JpaRepository<TradeTagMappingEntity, TradeTagMappingId> {

	List<TradeTagMappingEntity> findAllByTradeIdAndUserId(Long tradeId, Long userId);

	void deleteAllByTradeIdAndUserId(Long tradeId, Long userId);

	void deleteAllByTagIdAndUserId(Long tagId, Long userId);
}
