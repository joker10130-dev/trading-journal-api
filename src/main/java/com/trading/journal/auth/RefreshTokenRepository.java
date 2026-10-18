package com.trading.journal.auth;

import java.time.Instant;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select token from RefreshTokenEntity token
			where token.tokenHash = :tokenHash
			  and token.revokedAt is null
			  and token.expiresAt > :now
			""")
	Optional<RefreshTokenEntity> findValidForUpdate(
			@Param("tokenHash") String tokenHash,
			@Param("now") Instant now);

	Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

}
