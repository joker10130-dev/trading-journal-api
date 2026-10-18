package com.trading.journal.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import com.trading.journal.account.UserEntity;
import com.trading.journal.account.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private final UserRepository users;
	private final RefreshTokenRepository refreshTokens;
	private final PasswordEncoder passwordEncoder;
	private final AccessTokenService accessTokens;
	private final SignInRateLimiter rateLimiter;
	private final Clock clock;
	private final Duration refreshLifetime;

	public AuthService(
			UserRepository users,
			RefreshTokenRepository refreshTokens,
			PasswordEncoder passwordEncoder,
			AccessTokenService accessTokens,
			SignInRateLimiter rateLimiter,
			Clock jwtClock,
			@Value("${security.jwt.refresh-token-ttl}") Duration refreshLifetime) {
		this.users = users;
		this.refreshTokens = refreshTokens;
		this.passwordEncoder = passwordEncoder;
		this.accessTokens = accessTokens;
		this.rateLimiter = rateLimiter;
		this.clock = jwtClock;
		this.refreshLifetime = refreshLifetime;
		if (refreshLifetime.isNegative() || refreshLifetime.isZero()) {
			throw new IllegalArgumentException("JWT refresh-token lifetime must be positive.");
		}
	}

	@Transactional
	public AuthTokens signUp(String email, String password) {
		String normalizedEmail = normalizeEmail(email);
		validatePasswordByteLength(password);
		if (users.findByEmail(normalizedEmail).isPresent()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists.");
		}
		UserEntity user = users.saveAndFlush(new UserEntity(normalizedEmail, passwordEncoder.encode(password)));
		return createTokens(user);
	}

	@Transactional
	public AuthTokens signIn(String email, String password) {
		String normalizedEmail = normalizeEmail(email);
		Instant now = clock.instant();
		if (rateLimiter.isLimited(normalizedEmail, now)) {
			throw invalidCredentials();
		}
		Optional<UserEntity> matchingUser = users.findByEmail(normalizedEmail)
				.filter(user -> user.getPasswordHash() != null
						&& passwordEncoder.matches(password, user.getPasswordHash()));
		if (matchingUser.isEmpty()) {
			rateLimiter.recordFailure(normalizedEmail, now);
			throw invalidCredentials();
		}
		rateLimiter.recordSuccess(normalizedEmail);
		return createTokens(matchingUser.get());
	}

	@Transactional
	public Optional<AuthTokens> rotateRefreshToken(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return Optional.empty();
		}
		Instant now = clock.instant();
		RefreshTokenEntity current = refreshTokens.findValidForUpdate(hash(rawToken), now).orElse(null);
		if (current == null) {
			return Optional.empty();
		}
		UserEntity user = users.findById(current.getUserId()).orElse(null);
		if (user == null) {
			current.revoke(now);
			return Optional.empty();
		}
		current.revoke(now);
		return Optional.of(createTokens(user));
	}

	@Transactional
	public void signOut(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return;
		}
		refreshTokens.findByTokenHash(hash(rawToken)).ifPresent(token -> {
			if (token.getRevokedAt() == null) {
				token.revoke(clock.instant());
			}
		});
	}

	private AuthTokens createTokens(UserEntity user) {
		AccessTokenService.IssuedAccessToken accessToken = accessTokens.issue(user.getEmail());
		byte[] randomToken = new byte[32];
		SECURE_RANDOM.nextBytes(randomToken);
		String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomToken);
		Instant refreshExpiresAt = clock.instant().plus(refreshLifetime);
		refreshTokens.save(new RefreshTokenEntity(
				user.getId(), hash(refreshToken), clock.instant(), refreshExpiresAt));
		return new AuthTokens(accessToken.value(), accessToken.expiresAt(), refreshToken, refreshExpiresAt);
	}

	private String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private void validatePasswordByteLength(String password) {
		if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST, "Password must not exceed 72 UTF-8 bytes.");
		}
	}

	private String hash(String token) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private ResponseStatusException invalidCredentials() {
		return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email or password is incorrect.");
	}

	public record AuthTokens(
			String accessToken,
			Instant accessTokenExpiresAt,
			String refreshToken,
			Instant refreshTokenExpiresAt) {
	}

}
