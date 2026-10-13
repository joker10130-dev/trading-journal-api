package com.trading.journal.account;

import java.math.BigDecimal;
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
@Table(name = "account_transactions")
@Getter
@NoArgsConstructor
public class AccountTransactionEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "trading_account_id", nullable = false)
	private Long tradingAccountId;

	@Column(name = "import_batch_id", nullable = false)
	private Long importBatchId;

	@Column(nullable = false, length = 24)
	private String source;

	@Column(name = "source_fingerprint", nullable = false, length = 64, columnDefinition = "char(64)")
	@JdbcTypeCode(SqlTypes.CHAR)
	private String sourceFingerprint;

	@Column(name = "transaction_type", nullable = false, length = 16)
	private String transactionType;

	@Column(name = "occurred_at_server")
	private LocalDateTime occurredAtServer;

	@Column(nullable = false, precision = 18, scale = 5)
	private BigDecimal amount;

	@Column(nullable = false, length = 3, columnDefinition = "char(3)")
	@JdbcTypeCode(SqlTypes.CHAR)
	private String currency;

	@Column(length = 512)
	private String comment;

	public AccountTransactionEntity(
			Long userId,
			Long tradingAccountId,
			Long importBatchId,
			String sourceFingerprint,
			String transactionType,
			LocalDateTime occurredAtServer,
			BigDecimal amount,
			String currency,
			String comment) {
		this.userId = userId;
		this.tradingAccountId = tradingAccountId;
		this.importBatchId = importBatchId;
		this.source = "TRADES_REPORT";
		this.sourceFingerprint = sourceFingerprint;
		this.transactionType = transactionType;
		this.occurredAtServer = occurredAtServer;
		this.amount = amount;
		this.currency = currency;
		this.comment = comment;
	}

}
