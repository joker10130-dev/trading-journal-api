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
@Table(name = "trade_notes")
@Getter
@NoArgsConstructor
public class TradeNoteEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "trade_id", nullable = false, unique = true)
	private Long tradeId;

	@Column(name = "trading_account_id", nullable = false)
	private Long tradingAccountId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "text", nullable = false, columnDefinition = "text")
	private String text;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public TradeNoteEntity(Long tradeId, Long tradingAccountId, Long userId, String text) {
		this.tradeId = tradeId;
		this.tradingAccountId = tradingAccountId;
		this.userId = userId;
		this.text = text;
		this.updatedAt = Instant.now();
	}

	public void update(String text) {
		this.text = text;
		this.updatedAt = Instant.now();
	}
}
