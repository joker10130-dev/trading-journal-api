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
@Table(name = "trades")
@Getter
@NoArgsConstructor
public class TradeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "trading_account_id", nullable = false)
	private Long tradingAccountId;

	@Column(nullable = false, length = 64)
	private String ticket;

	@Column(nullable = false, length = 32)
	private String symbol;

	@Column(nullable = false, length = 8)
	private String side;

	@Column(nullable = false, length = 24)
	private String status;

	@Column(name = "opened_at")
	private Instant openedAt;

	@Column(name = "open_time_server")
	@JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
	private LocalDateTime openTimeServer;

	@Column(name = "open_price", precision = 18, scale = 5)
	private BigDecimal openPrice;

	@Column(name = "opened_volume", precision = 18, scale = 5)
	private BigDecimal openedVolume;

	@Column(name = "initial_sl_price", precision = 18, scale = 5)
	private BigDecimal initialSlPrice;

	@Column(name = "risk_amount", precision = 18, scale = 5)
	private BigDecimal riskAmount;

	@Column(name = "risk_source", nullable = false, length = 24)
	private String riskSource;

	@Column(name = "r_multiple", precision = 18, scale = 8)
	private BigDecimal rMultiple;

	@Column(name = "r_flag", length = 32)
	private String rFlag;

	@Column(name = "r_source", nullable = false, length = 24)
	private String rSource;

	@Column(name = "gross_pnl", nullable = false, precision = 18, scale = 5)
	private BigDecimal grossPnl;

	@Column(name = "fees_and_swap", nullable = false, precision = 18, scale = 5)
	private BigDecimal feesAndSwap;

	@Column(name = "net_pnl", nullable = false, precision = 18, scale = 5)
	private BigDecimal netPnl;

	@Column(name = "has_trades_report", nullable = false)
	private boolean hasTradesReport;

	@Column(name = "has_tradingview_data", nullable = false)
	private boolean hasTradingViewData;

	public TradeEntity(
			Long userId,
			Long tradingAccountId,
			String ticket,
			String symbol,
			String side,
			LocalDateTime openTimeServer,
			Instant openedAt,
			BigDecimal openPrice) {
		this(userId, tradingAccountId, ticket, symbol, side, openTimeServer, openedAt, openPrice, true, false);
	}

	public TradeEntity(
			Long userId,
			Long tradingAccountId,
			String ticket,
			String symbol,
			String side,
			LocalDateTime openTimeServer,
			Instant openedAt,
			BigDecimal openPrice,
			boolean hasTradesReport,
			boolean hasTradingViewData) {
		this.userId = userId;
		this.tradingAccountId = tradingAccountId;
		this.ticket = ticket;
		this.symbol = symbol;
		this.side = side;
		this.status = "CLOSED";
		this.openTimeServer = openTimeServer;
		this.openedAt = openedAt;
		this.openPrice = openPrice;
		this.openedVolume = BigDecimal.ZERO;
		this.riskSource = "NONE";
		this.rSource = "NONE";
		this.grossPnl = BigDecimal.ZERO;
		this.feesAndSwap = BigDecimal.ZERO;
		this.netPnl = BigDecimal.ZERO;
		this.hasTradesReport = hasTradesReport;
		this.hasTradingViewData = hasTradingViewData;
	}

	public void replaceTradingViewData(
			String symbol,
			String side,
			String status,
			LocalDateTime openTimeServer,
			Instant openedAt,
			BigDecimal openPrice,
			BigDecimal openedVolume,
			BigDecimal grossPnl,
			BigDecimal feesAndSwap,
			BigDecimal netPnl) {
		this.symbol = symbol;
		this.side = side;
		this.status = status;
		this.openTimeServer = openTimeServer;
		this.openedAt = openedAt;
		this.openPrice = openPrice;
		this.openedVolume = openedVolume;
		this.grossPnl = grossPnl;
		this.feesAndSwap = feesAndSwap;
		this.netPnl = netPnl;
		this.hasTradingViewData = true;
	}

	public void markHasTradingViewData() {
		this.hasTradingViewData = true;
	}

	public void updateRisk(
			BigDecimal initialSlPrice,
			BigDecimal riskAmount,
			String riskSource,
			BigDecimal rMultiple,
			String rFlag,
			String rSource) {
		this.initialSlPrice = initialSlPrice;
		this.riskAmount = riskAmount;
		this.riskSource = riskSource;
		this.rMultiple = rMultiple;
		this.rFlag = rFlag;
		this.rSource = rSource;
	}

	public void applyManualR(BigDecimal rMultiple) {
		this.rMultiple = rMultiple;
		this.rFlag = null;
		this.rSource = "MANUAL";
	}

	public void replaceWithTradesReport(
			String symbol,
			String side,
			LocalDateTime openTimeServer,
			Instant openedAt,
			BigDecimal openPrice) {
		this.symbol = symbol;
		this.side = side;
		this.status = "CLOSED";
		this.openTimeServer = openTimeServer;
		this.openedAt = openedAt;
		this.openPrice = openPrice;
		this.openedVolume = null;
		this.grossPnl = BigDecimal.ZERO;
		this.feesAndSwap = BigDecimal.ZERO;
		this.netPnl = BigDecimal.ZERO;
		this.hasTradesReport = true;
	}

	public void addClose(
			BigDecimal volume,
			BigDecimal gross,
			BigDecimal fees,
			BigDecimal net) {
		this.openedVolume = (this.openedVolume == null ? BigDecimal.ZERO : this.openedVolume).add(volume);
		this.grossPnl = this.grossPnl.add(gross);
		this.feesAndSwap = this.feesAndSwap.add(fees);
		this.netPnl = this.netPnl.add(net);
	}

}
