package com.trading.journal.account;

import java.util.List;
import java.util.Locale;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1/trading-accounts")
public class TradingAccountController {

	private final CurrentUserService currentUserService;
	private final TradingAccountRepository accounts;

	public TradingAccountController(CurrentUserService currentUserService, TradingAccountRepository accounts) {
		this.currentUserService = currentUserService;
		this.accounts = accounts;
	}

	@GetMapping
	public List<AccountResponse> list() {
		UserEntity user = currentUserService.requireCurrentUser();
		return accounts.findAllByUserIdOrderById(user.getId()).stream()
				.map(AccountResponse::from)
				.toList();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
		UserEntity user = currentUserService.getOrCreateCurrentUser();
		String name = normalizeName(request.name());
		if (accounts.existsByUserIdAndNameIgnoreCase(user.getId(), name)) {
			throw duplicateAccountName();
		}
		TradingAccountEntity account = accounts.saveAndFlush(new TradingAccountEntity(
				user.getId(),
				name,
				request.broker().trim(),
				request.currency().toUpperCase(Locale.ROOT),
				normalizeBrokerLogin(request.brokerLogin()),
				normalizeOffset(request.tradesReportUtcOffsetMinutes())));
		return AccountResponse.from(account);
	}

	@PutMapping("/{accountId}")
	@Transactional
	public AccountResponse update(
			@PathVariable Long accountId,
			@Valid @RequestBody CreateAccountRequest request) {
		UserEntity user = currentUserService.requireCurrentUser();
		TradingAccountEntity account = accounts.findByIdAndUserIdForUpdate(accountId, user.getId())
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "Trading account was not found."));
		String name = normalizeName(request.name());
		if (accounts.existsByUserIdAndNameIgnoreCaseAndIdNot(user.getId(), name, accountId)) {
			throw duplicateAccountName();
		}
		account.updateDetails(
				name,
				request.broker().trim(),
				request.currency().toUpperCase(Locale.ROOT),
				normalizeBrokerLogin(request.brokerLogin()),
				normalizeOffset(request.tradesReportUtcOffsetMinutes()));
		return AccountResponse.from(accounts.saveAndFlush(account));
	}

	private String normalizeName(String name) {
		return name.trim();
	}

	private String normalizeBrokerLogin(String brokerLogin) {
		return brokerLogin == null || brokerLogin.isBlank() ? null : brokerLogin.trim();
	}

	private Short normalizeOffset(Short offset) {
		if (offset != null && (offset < -840 || offset > 840)) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST, "UTC offset must be between -840 and 840 minutes.");
		}
		return offset;
	}

	private ResponseStatusException duplicateAccountName() {
		return new ResponseStatusException(HttpStatus.CONFLICT, "A trading account with this name already exists.");
	}

	public record CreateAccountRequest(
			@NotBlank @Size(max = 120) String name,
			@NotBlank @Size(max = 120) String broker,
			@NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
			@Size(max = 64) String brokerLogin,
			Short tradesReportUtcOffsetMinutes) {
	}

	public record AccountResponse(
			Long id,
			String name,
			String broker,
			String currency,
			String brokerLogin,
			Short tradesReportUtcOffsetMinutes) {

		private static AccountResponse from(TradingAccountEntity account) {
			return new AccountResponse(
					account.getId(),
					account.getName(),
					account.getBroker(),
					account.getCurrency(),
					account.getBrokerLogin(),
					account.getTradesReportUtcOffsetMinutes());
		}
	}

}
