package com.trading.journal.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
public class AccessTokenService {

	private final JwtEncoder encoder;
	private final Clock clock;
	private final String issuer;
	private final Duration lifetime;

	public AccessTokenService(
			JwtEncoder encoder,
			Clock jwtClock,
			@Value("${security.jwt.issuer}") String issuer,
			@Value("${security.jwt.access-token-ttl}") Duration lifetime) {
		this.encoder = encoder;
		this.clock = jwtClock;
		this.issuer = issuer;
		this.lifetime = lifetime;
		if (lifetime.isNegative() || lifetime.isZero()) {
			throw new IllegalArgumentException("JWT access-token lifetime must be positive.");
		}
	}

	public IssuedAccessToken issue(String subject) {
		Instant issuedAt = clock.instant();
		Instant expiresAt = issuedAt.plus(lifetime);
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(issuer)
				.subject(subject)
				.issuedAt(issuedAt)
				.expiresAt(expiresAt)
				.id(UUID.randomUUID().toString())
				.build();
		String token = encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
		return new IssuedAccessToken(token, expiresAt);
	}

	public record IssuedAccessToken(String value, Instant expiresAt) {
	}

}
