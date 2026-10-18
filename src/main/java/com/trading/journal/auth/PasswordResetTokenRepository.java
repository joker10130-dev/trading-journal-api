package com.trading.journal.auth;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select token from PasswordResetTokenEntity token where token.tokenHash = :tokenHash")
	Optional<PasswordResetTokenEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

	List<PasswordResetTokenEntity> findAllByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNull(Long userId);

}
