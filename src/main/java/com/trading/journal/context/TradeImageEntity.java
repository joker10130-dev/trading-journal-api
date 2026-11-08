package com.trading.journal.context;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "trade_images")
@Getter
@NoArgsConstructor
public class TradeImageEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "trade_id", nullable = false)
	private Long tradeId;

	@Column(name = "trading_account_id", nullable = false)
	private Long tradingAccountId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "storage_key", nullable = false, length = 64, unique = true)
	private String storageKey;

	@Column(name = "content_type", nullable = false, length = 32)
	private String contentType;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	public TradeImageEntity(
			Long tradeId,
			Long tradingAccountId,
			Long userId,
			String storageKey,
			String contentType) {
		this.tradeId = tradeId;
		this.tradingAccountId = tradingAccountId;
		this.userId = userId;
		this.storageKey = storageKey;
		this.contentType = contentType;
		this.createdAt = Instant.now();
	}
}
