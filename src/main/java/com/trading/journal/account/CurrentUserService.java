package com.trading.journal.account;

import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CurrentUserService {

	private final UserRepository users;

	public CurrentUserService(UserRepository users) {
		this.users = users;
	}

	@Transactional
	public UserEntity getOrCreateCurrentUser() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication.getName() == null || authentication.getName().isBlank()
				|| "anonymousUser".equals(authentication.getName())) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "An authenticated user is required.");
		}

		String email = authentication.getName().trim().toLowerCase(Locale.ROOT);
		return users.findByEmail(email).orElseGet(() -> users.saveAndFlush(new UserEntity(email)));
	}

	@Transactional(readOnly = true)
	public UserEntity requireCurrentUser() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication.getName() == null || authentication.getName().isBlank()
				|| "anonymousUser".equals(authentication.getName())) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "An authenticated user is required.");
		}

		String email = authentication.getName().trim().toLowerCase(Locale.ROOT);
		return users.findByEmail(email)
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.UNAUTHORIZED, "Authenticated user record was not found."));
	}

}
