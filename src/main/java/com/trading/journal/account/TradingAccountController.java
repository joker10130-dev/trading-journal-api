package com.trading.journal.account;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMin;
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

@RestController
@RequestMapping("/api/v1/trading-accounts")
public class TradingAccountController {

	private final CurrentUserService currentUserService;
	private final TradingAccountRepository accounts;
	private final TradingAccountRiskService accountRiskService;

	public TradingAccountController(
			CurrentUserService currentUserService,
			TradingAccountRepository accounts,
			TradingAccountRiskService accountRiskService) {
		this.currentUserService = currentUserService;
		this.accounts = accounts;
		this.accountRiskService = accountRiskService;
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
		String name = request.name().trim();
		if (accounts.existsByUserIdAndNameIgnoreCase(user.getId(), name)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A trading account with this name already exists.");
		}
		String brokerLogin = request.brokerLogin() == null || request.brokerLogin().isBlank()
				? null
				: request.brokerLogin().trim();
		Short offset = request.tradesReportUtcOffsetMinutes();
		if (offset != null && (offset < -840 || offset > 840)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UTC offset must be between -840 and 840 minutes.");
		}
		if ((request.defaultRiskMode() == null) != (request.defaultRiskValue() == null)) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST, "Default risk mode and value must be provided together.");
		}
		TradingAccountEntity account = accounts.saveAndFlush(new TradingAccountEntity(
				user.getId(),
				name,
				request.broker().trim(),
				request.currency().toUpperCase(Locale.ROOT),
				brokerLogin,
				offset,
				request.defaultRiskMode() == null ? null : request.defaultRiskMode().toUpperCase(Locale.ROOT),
				request.defaultRiskValue()));
		return AccountResponse.from(account);
	}

	@PutMapping("/{accountId}/risk-rule")
	public AccountResponse updateRiskRule(
			@PathVariable Long accountId,
			@Valid @RequestBody RiskRuleRequest request) {
		UserEntity user = currentUserService.requireCurrentUser();
		String mode = request.defaultRiskMode() == null
				? null
				: request.defaultRiskMode().toUpperCase(Locale.ROOT);
		return AccountResponse.from(accountRiskService.updateRule(
				accountId, user.getId(), mode, request.defaultRiskValue()));
	}

	public record CreateAccountRequest(
			@NotBlank @Size(max = 120) String name,
			@NotBlank @Size(max = 120) String broker,
			@NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
			@Size(max = 64) String brokerLogin,
			Short tradesReportUtcOffsetMinutes,
			@Pattern(regexp = "FIXED_AMOUNT|FIXED_STOP_DISTANCE", flags = Pattern.Flag.CASE_INSENSITIVE)
					String defaultRiskMode,
			@DecimalMin(value = "0.00001") BigDecimal defaultRiskValue) {
	}

	public record RiskRuleRequest(
			@Pattern(regexp = "FIXED_AMOUNT|FIXED_STOP_DISTANCE", flags = Pattern.Flag.CASE_INSENSITIVE)
					String defaultRiskMode,
			@DecimalMin(value = "0.00001") BigDecimal defaultRiskValue) {
	}

	public record AccountResponse(
			Long id,
			String name,
			String broker,
			String currency,
			String brokerLogin,
			Short tradesReportUtcOffsetMinutes,
			String defaultRiskMode,
			BigDecimal defaultRiskValue) {

		private static AccountResponse from(TradingAccountEntity account) {
			return new AccountResponse(
					account.getId(),
					account.getName(),
					account.getBroker(),
					account.getCurrency(),
					account.getBrokerLogin(),
					account.getTradesReportUtcOffsetMinutes(),
					account.getDefaultRiskMode(),
					account.getDefaultRiskValue());
		}
	}

}
