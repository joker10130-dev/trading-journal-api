package com.trading.journal.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "trade_closes")
@Getter
@NoArgsConstructor
public class TradeCloseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "trading_account_id", nullable = false)
	private Long tradingAccountId;

	@Column(name = "trade_id", nullable = false)
	private Long tradeId;

	@Column(name = "import_batch_id", nullable = false)
	private Long importBatchId;

	@Column(nullable = false, length = 24)
	private String source;

	@Column(name = "source_fingerprint", nullable = false, length = 64, columnDefinition = "char(64)")
	@JdbcTypeCode(SqlTypes.CHAR)
	private String sourceFingerprint;

	@Column(nullable = false, precision = 18, scale = 5)
	private BigDecimal volume;

	@Column(name = "close_time_server")
	private LocalDateTime closeTimeServer;

	@Column(name = "closed_at")
	private Instant closedAt;

	@Column(name = "close_price", nullable = false, precision = 18, scale = 5)
	private BigDecimal closePrice;

	@Column(name = "gross_pnl", nullable = false, precision = 18, scale = 5)
	private BigDecimal grossPnl;

	@Column(nullable = false, precision = 18, scale = 5)
	private BigDecimal swaps;

	@Column(nullable = false, precision = 18, scale = 5)
	private BigDecimal commission;

	@Column(name = "net_pnl", nullable = false, precision = 18, scale = 5)
	private BigDecimal netPnl;

	@Column(name = "r_multiple", precision = 18, scale = 8)
	private BigDecimal rMultiple;

	public TradeCloseEntity(
			Long userId,
			Long tradingAccountId,
			Long tradeId,
			Long importBatchId,
			String sourceFingerprint,
			BigDecimal volume,
			LocalDateTime closeTimeServer,
			Instant closedAt,
			BigDecimal closePrice,
			BigDecimal grossPnl,
			BigDecimal swaps,
			BigDecimal commission,
			BigDecimal netPnl) {
		this(userId, tradingAccountId, tradeId, importBatchId, "TRADES_REPORT",
				sourceFingerprint, volume, closeTimeServer, closedAt, closePrice,
				grossPnl, swaps, commission, netPnl);
	}

	public TradeCloseEntity(
			Long userId,
			Long tradingAccountId,
			Long tradeId,
			Long importBatchId,
			String source,
			String sourceFingerprint,
			BigDecimal volume,
			LocalDateTime closeTimeServer,
			Instant closedAt,
			BigDecimal closePrice,
			BigDecimal grossPnl,
			BigDecimal swaps,
			BigDecimal commission,
			BigDecimal netPnl) {
		this.userId = userId;
		this.tradingAccountId = tradingAccountId;
		this.tradeId = tradeId;
		this.importBatchId = importBatchId;
		this.source = source;
		this.sourceFingerprint = sourceFingerprint;
		this.volume = volume;
		this.closeTimeServer = closeTimeServer;
		this.closedAt = closedAt;
		this.closePrice = closePrice;
		this.grossPnl = grossPnl;
		this.swaps = swaps;
		this.commission = commission;
		this.netPnl = netPnl;
	}

	public void updateRMultiple(BigDecimal rMultiple) {
		this.rMultiple = rMultiple;
	}

}
