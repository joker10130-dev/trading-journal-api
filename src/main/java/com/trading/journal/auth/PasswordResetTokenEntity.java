package com.trading.journal.auth;

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
@Table(name = "password_reset_tokens")
@Getter
@NoArgsConstructor
public class PasswordResetTokenEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "token_hash", nullable = false, unique = true, length = 64, columnDefinition = "char(64)")
	@JdbcTypeCode(SqlTypes.CHAR)
	private String tokenHash;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "consumed_at")
	private Instant consumedAt;

	@Column(name = "invalidated_at")
	private Instant invalidatedAt;

	public PasswordResetTokenEntity(Long userId, String tokenHash, Instant createdAt, Instant expiresAt) {
		this.userId = userId;
		this.tokenHash = tokenHash;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	public void consume(Instant consumedAt) {
		this.consumedAt = consumedAt;
	}

	public void invalidate(Instant invalidatedAt) {
		this.invalidatedAt = invalidatedAt;
	}

}
