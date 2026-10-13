package com.trading.journal.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
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
@Table(name = "trade_orders")
@Getter
@NoArgsConstructor
public class TradeOrderEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "trading_account_id", nullable = false)
	private Long tradingAccountId;

	@Column(name = "import_batch_id", nullable = false)
	private Long importBatchId;

	@Column(name = "trade_id")
	private Long tradeId;

	@Column(name = "external_order_id", nullable = false, length = 64)
	private String externalOrderId;

	@Column(name = "position_id", length = 96)
	private String positionId;

	@Column(length = 32)
	private String symbol;

	@Column(length = 8)
	private String side;

	@Column(name = "order_type", length = 24)
	private String orderType;

	@Column(nullable = false, length = 24)
	private String status;

	@Column(precision = 18, scale = 5)
	private BigDecimal volume;

	@Column(name = "update_time_server")
	private LocalDateTime updateTimeServer;

	@Column(name = "updated_at")
	private Instant updatedAt;

	@Column(name = "order_price", precision = 18, scale = 5)
	private BigDecimal orderPrice;

	@Column(name = "average_fill_price", precision = 18, scale = 5)
	private BigDecimal averageFillPrice;

	@Column(name = "stop_price", precision = 18, scale = 5)
	private BigDecimal stopPrice;

	@Column(name = "limit_price", precision = 18, scale = 5)
	private BigDecimal limitPrice;

	@Column(name = "closed_pnl", precision = 18, scale = 5)
	private BigDecimal closedPnl;

	@Column(name = "net_closed_pnl", precision = 18, scale = 5)
	private BigDecimal netClosedPnl;

	@Column(precision = 18, scale = 5)
	private BigDecimal commission;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "raw_row", nullable = false, columnDefinition = "jsonb")
	private Map<String, String> rawRow;

	public TradeOrderEntity(
			Long userId,
			Long tradingAccountId,
			Long importBatchId,
			String externalOrderId,
			String positionId,
			String symbol,
			String side,
			String orderType,
			String status,
			BigDecimal volume,
			LocalDateTime updateTimeServer,
			Instant updatedAt,
			BigDecimal orderPrice,
			BigDecimal averageFillPrice,
			BigDecimal stopPrice,
			BigDecimal limitPrice,
			BigDecimal closedPnl,
			BigDecimal netClosedPnl,
			BigDecimal commission,
			Map<String, String> rawRow) {
		this.userId = userId;
		this.tradingAccountId = tradingAccountId;
		this.importBatchId = importBatchId;
		this.externalOrderId = externalOrderId;
		this.positionId = positionId;
		this.symbol = symbol;
		this.side = side;
		this.orderType = orderType;
		this.status = status;
		this.volume = volume;
		this.updateTimeServer = updateTimeServer;
		this.updatedAt = updatedAt;
		this.orderPrice = orderPrice;
		this.averageFillPrice = averageFillPrice;
		this.stopPrice = stopPrice;
		this.limitPrice = limitPrice;
		this.closedPnl = closedPnl;
		this.netClosedPnl = netClosedPnl;
		this.commission = commission;
		this.rawRow = Map.copyOf(rawRow);
	}

	public void attachToTrade(Long tradeId) {
		this.tradeId = tradeId;
	}

}
