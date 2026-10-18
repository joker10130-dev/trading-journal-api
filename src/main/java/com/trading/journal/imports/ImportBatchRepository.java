package com.trading.journal.imports;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportBatchRepository extends JpaRepository<ImportBatchEntity, Long> {

	Optional<ImportBatchEntity> findByIdAndTradingAccountIdAndUserId(
			Long id,
			Long tradingAccountId,
			Long userId);

}
