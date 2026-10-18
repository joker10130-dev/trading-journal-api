package com.trading.journal.account;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TradingAccountRepository extends JpaRepository<TradingAccountEntity, Long> {

	List<TradingAccountEntity> findAllByUserIdOrderById(Long userId);

	Optional<TradingAccountEntity> findByIdAndUserId(Long id, Long userId);

	boolean existsByUserIdAndNameIgnoreCase(Long userId, String name);

	boolean existsByUserIdAndNameIgnoreCaseAndIdNot(Long userId, String name, Long id);

	List<TradingAccountEntity> findAllByIdInAndUserId(Collection<Long> ids, Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select account from TradingAccountEntity account where account.id = :id and account.userId = :userId")
	Optional<TradingAccountEntity> findByIdAndUserIdForUpdate(
			@Param("id") Long id,
			@Param("userId") Long userId);

}
