package com.trading.journal.context;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "trade_tag_mappings")
@IdClass(TradeTagMappingId.class)
@Getter
@NoArgsConstructor
public class TradeTagMappingEntity {

	@Id
	@Column(name = "trade_id")
	private Long tradeId;

	@Id
	@Column(name = "tag_id")
	private Long tagId;

	@Column(name = "trading_account_id", nullable = false)
	private Long tradingAccountId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	public TradeTagMappingEntity(Long tradeId, Long tagId, Long tradingAccountId, Long userId) {
		this.tradeId = tradeId;
		this.tagId = tagId;
		this.tradingAccountId = tradingAccountId;
		this.userId = userId;
	}
}
