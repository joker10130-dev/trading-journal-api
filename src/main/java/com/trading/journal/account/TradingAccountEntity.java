package com.trading.journal.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "trading_accounts")
@Getter
@NoArgsConstructor
public class TradingAccountEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(nullable = false, length = 120)
	private String broker;

	@Column(nullable = false, length = 3, columnDefinition = "char(3)")
	@JdbcTypeCode(SqlTypes.CHAR)
	private String currency;

	@Column(name = "broker_login", length = 64)
	private String brokerLogin;

	@Column(name = "trades_report_utc_offset_minutes")
	private Short tradesReportUtcOffsetMinutes;

	@Column(name = "default_risk_mode", length = 24)
	private String defaultRiskMode;

	@Column(name = "default_risk_value", precision = 18, scale = 5)
	private BigDecimal defaultRiskValue;

	public TradingAccountEntity(
			Long userId,
			String name,
			String broker,
			String currency,
			String brokerLogin,
			Short tradesReportUtcOffsetMinutes) {
		this(userId, name, broker, currency, brokerLogin, tradesReportUtcOffsetMinutes, null, null);
	}

	public TradingAccountEntity(
			Long userId,
			String name,
			String broker,
			String currency,
			String brokerLogin,
			Short tradesReportUtcOffsetMinutes,
			String defaultRiskMode,
			BigDecimal defaultRiskValue) {
		this.userId = userId;
		this.name = name;
		this.broker = broker;
		this.currency = currency;
		this.brokerLogin = brokerLogin;
		this.tradesReportUtcOffsetMinutes = tradesReportUtcOffsetMinutes;
		this.defaultRiskMode = defaultRiskMode;
		this.defaultRiskValue = defaultRiskValue;
	}

	public void updateDefaultRiskRule(String defaultRiskMode, BigDecimal defaultRiskValue) {
		this.defaultRiskMode = defaultRiskMode;
		this.defaultRiskValue = defaultRiskValue;
	}

}
