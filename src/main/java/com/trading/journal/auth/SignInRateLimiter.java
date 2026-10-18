package com.trading.journal.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SignInRateLimiter {

	private static final int MAX_FAILURES = 5;
	private static final int MAX_TRACKED_EMAILS = 10_000;
	private static final Duration WINDOW = Duration.ofMinutes(15);

	private final Map<String, ArrayDeque<Instant>> failuresByEmail = new LinkedHashMap<>();

	public synchronized boolean isLimited(String email, Instant now) {
		prune(now);
		ArrayDeque<Instant> failures = failuresByEmail.get(fingerprint(email));
		return failures != null && failures.size() >= MAX_FAILURES;
	}

	public synchronized void recordFailure(String email, Instant now) {
		prune(now);
		String fingerprint = fingerprint(email);
		failuresByEmail.computeIfAbsent(fingerprint, ignored -> new ArrayDeque<>()).addLast(now);
		while (failuresByEmail.size() > MAX_TRACKED_EMAILS) {
			failuresByEmail.remove(failuresByEmail.keySet().iterator().next());
		}
	}

	public synchronized void recordSuccess(String email) {
		failuresByEmail.remove(fingerprint(email));
	}

	private void prune(Instant now) {
		Iterator<Map.Entry<String, ArrayDeque<Instant>>> entries = failuresByEmail.entrySet().iterator();
		while (entries.hasNext()) {
			ArrayDeque<Instant> failures = entries.next().getValue();
			while (!failures.isEmpty() && !failures.peekFirst().isAfter(now.minus(WINDOW))) {
				failures.removeFirst();
			}
			if (failures.isEmpty()) {
				entries.remove();
			}
		}
	}

	private String fingerprint(String email) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(email.getBytes(StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

}
