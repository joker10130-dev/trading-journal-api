package com.trading.journal.imports;

import java.time.Instant;
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
@Table(name = "import_batches")
@Getter
@NoArgsConstructor
public class ImportBatchEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "trading_account_id", nullable = false)
	private Long tradingAccountId;

	@Column(nullable = false, length = 24)
	private String source;

	@Column(nullable = false, length = 255)
	private String filename;

	@Column(name = "file_sha256", nullable = false, length = 64, columnDefinition = "char(64)")
	@JdbcTypeCode(SqlTypes.CHAR)
	private String fileSha256;

	@Column(name = "imported_at", nullable = false, insertable = false, updatable = false)
	private Instant importedAt;

	@Column(name = "total_rows", nullable = false)
	private int totalRows;

	@Column(name = "duplicate_rows", nullable = false)
	private int duplicateRows;

	@Column(name = "imported_rows", nullable = false)
	private int importedRows;

	@Column(name = "non_trade_rows", nullable = false)
	private int nonTradeRows;

	@Column(name = "error_rows", nullable = false)
	private int errorRows;

	public ImportBatchEntity(
			Long userId,
			Long tradingAccountId,
			String filename,
			String fileSha256,
			int totalRows,
			int duplicateRows,
			int importedRows,
			int nonTradeRows) {
		this(userId, tradingAccountId, "TRADES_REPORT", filename, fileSha256,
				totalRows, duplicateRows, importedRows, nonTradeRows);
	}

	public ImportBatchEntity(
			Long userId,
			Long tradingAccountId,
			String source,
			String filename,
			String fileSha256,
			int totalRows,
			int duplicateRows,
			int importedRows,
			int nonTradeRows) {
		this.userId = userId;
		this.tradingAccountId = tradingAccountId;
		this.source = source;
		this.filename = filename;
		this.fileSha256 = fileSha256;
		this.totalRows = totalRows;
		this.duplicateRows = duplicateRows;
		this.importedRows = importedRows;
		this.nonTradeRows = nonTradeRows;
		this.errorRows = 0;
	}

}
