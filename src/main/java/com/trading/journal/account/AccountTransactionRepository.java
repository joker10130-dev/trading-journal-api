package com.trading.journal.account;

import java.util.List;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountTransactionRepository extends JpaRepository<AccountTransactionEntity, Long> {

	List<AccountTransactionEntity> findAllByTradingAccountIdAndSourceFingerprintIn(
			Long tradingAccountId,
			Collection<String> fingerprints);

	List<AccountTransactionEntity> findAllByImportBatchId(Long importBatchId);

}
