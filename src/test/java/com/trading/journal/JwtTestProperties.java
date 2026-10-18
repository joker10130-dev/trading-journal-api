package com.trading.journal;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class JwtTestProperties {

	private static final String TEST_JWT_SECRET = generateSecret();

	@DynamicPropertySource
	protected static void jwtProperties(DynamicPropertyRegistry registry) {
		registry.add("security.jwt.secret-base64", () -> TEST_JWT_SECRET);
		registry.add("security.jwt.refresh-cookie-secure", () -> false);
	}

	private static String generateSecret() {
		byte[] secret = new byte[32];
		new SecureRandom().nextBytes(secret);
		return Base64.getEncoder().encodeToString(secret);
	}

}
