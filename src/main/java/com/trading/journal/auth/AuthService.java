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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();
	private static final String PASSWORD_RESET_MESSAGE =
			"If an account exists for this email, password-reset instructions will be sent.";
	private static final String INVALID_RESET_TOKEN_MESSAGE = "Reset link is invalid or expired.";

	private final UserRepository users;
	private final RefreshTokenRepository refreshTokens;
	private final PasswordResetTokenRepository passwordResetTokens;
	private final PasswordEncoder passwordEncoder;
	private final AccessTokenService accessTokens;
	private final PasswordResetEmailSender passwordResetEmailSender;
	private final SignInRateLimiter rateLimiter;
	private final Clock clock;
	private final Duration refreshLifetime;
	private final Duration passwordResetLifetime;
	private final String passwordResetFrontendUrl;

	public AuthService(
			UserRepository users,
			RefreshTokenRepository refreshTokens,
			PasswordResetTokenRepository passwordResetTokens,
			PasswordEncoder passwordEncoder,
			AccessTokenService accessTokens,
			PasswordResetEmailSender passwordResetEmailSender,
			SignInRateLimiter rateLimiter,
			Clock jwtClock,
			@Value("${security.jwt.refresh-token-ttl}") Duration refreshLifetime,
			@Value("${security.password-reset.token-ttl:PT30M}") Duration passwordResetLifetime,
			@Value("${security.password-reset.frontend-url}") String passwordResetFrontendUrl) {
		this.users = users;
		this.refreshTokens = refreshTokens;
		this.passwordResetTokens = passwordResetTokens;
		this.passwordEncoder = passwordEncoder;
		this.accessTokens = accessTokens;
		this.passwordResetEmailSender = passwordResetEmailSender;
		this.rateLimiter = rateLimiter;
		this.clock = jwtClock;
		this.refreshLifetime = refreshLifetime;
		this.passwordResetLifetime = passwordResetLifetime;
		this.passwordResetFrontendUrl = passwordResetFrontendUrl;
		if (refreshLifetime.isNegative() || refreshLifetime.isZero()) {
			throw new IllegalArgumentException("JWT refresh-token lifetime must be positive.");
		}
		if (passwordResetLifetime.isNegative() || passwordResetLifetime.isZero()) {
			throw new IllegalArgumentException("Password-reset token lifetime must be positive.");
		}
	}

	@Transactional
	public AuthTokens signUp(String email, String password) {
		String normalizedEmail = normalizeEmail(email);
		validatePasswordByteLength(password);
		if (users.findByEmail(normalizedEmail).isPresent()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Unable to create account with these details.");
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

	@Transactional
	public String requestPasswordReset(String email) {
		String normalizedEmail = normalizeEmail(email);
		UserEntity user = users.findByEmail(normalizedEmail).orElse(null);
		if (user == null) {
			return PASSWORD_RESET_MESSAGE;
		}

		Instant now = clock.instant();
		passwordResetTokens.findAllByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNull(user.getId())
				.forEach(token -> token.invalidate(now));
		byte[] randomToken = new byte[32];
		SECURE_RANDOM.nextBytes(randomToken);
		String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomToken);
		PasswordResetTokenEntity resetToken = passwordResetTokens.saveAndFlush(new PasswordResetTokenEntity(
				user.getId(),
				hash(rawToken),
				now,
				now.plus(passwordResetLifetime)));
		String resetUrl = org.springframework.web.util.UriComponentsBuilder
				.fromUriString(passwordResetFrontendUrl)
				.queryParam("token", rawToken)
				.build()
				.encode()
				.toUriString();
		try {
			passwordResetEmailSender.sendPasswordResetEmail(user.getEmail(), resetUrl);
		} catch (PasswordResetEmailException exception) {
			resetToken.invalidate(now);
			log.error("Password-reset email delivery failed; request response remains generic.", exception);
		}
		return PASSWORD_RESET_MESSAGE;
	}

	@Transactional
	public void resetPassword(String rawToken, String newPassword) {
		validatePasswordByteLength(newPassword);
		if (rawToken == null || rawToken.isBlank()) {
			throw invalidResetToken();
		}
		PasswordResetTokenEntity resetToken = passwordResetTokens.findByTokenHashForUpdate(hash(rawToken))
				.orElseThrow(this::invalidResetToken);
		Instant now = clock.instant();
		if (resetToken.getConsumedAt() != null
				|| resetToken.getInvalidatedAt() != null
				|| !resetToken.getExpiresAt().isAfter(now)) {
			throw invalidResetToken();
		}
		UserEntity user = users.findById(resetToken.getUserId()).orElseThrow(this::invalidResetToken);
		resetToken.consume(now);
		user.updatePasswordHash(passwordEncoder.encode(newPassword));
		refreshTokens.findAllByUserIdAndRevokedAtIsNull(user.getId())
				.forEach(refreshToken -> refreshToken.revoke(now));
		users.save(user);
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
		if (password.codePointCount(0, password.length()) < 10) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 10 characters.");
		}
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

	private ResponseStatusException invalidResetToken() {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_RESET_TOKEN_MESSAGE);
	}

	public record AuthTokens(
			String accessToken,
			Instant accessTokenExpiresAt,
			String refreshToken,
			Instant refreshTokenExpiresAt) {
	}

}
