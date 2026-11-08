package com.trading.journal.account;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/trading-accounts")
public class AccountSummaryController {

	private final CurrentUserService currentUserService;
	private final TradingAccountRepository accounts;
	private final JdbcClient jdbcClient;

	public AccountSummaryController(
			CurrentUserService currentUserService,
			TradingAccountRepository accounts,
			JdbcClient jdbcClient) {
		this.currentUserService = currentUserService;
		this.accounts = accounts;
		this.jdbcClient = jdbcClient;
	}

	@GetMapping("/summary")
	public AccountSummary summary(
			@RequestParam(required = false) List<Long> accountIds,
			@RequestParam(required = false) List<String> symbols) {
		UserEntity user = currentUserService.requireCurrentUser();
		List<Long> selectedAccountIds = validateAccountIds(user.getId(), accountIds);
		StringBuilder sql = new StringBuilder("""
				select count(*) as trade_count,
				       coalesce(sum(gross_pnl), 0) as gross_pnl,
				       coalesce(sum(fees_and_swap), 0) as fees_and_swap,
				       coalesce(sum(net_pnl), 0) as net_pnl
				from trades t
				where t.user_id = ? and t.deleted_at is null
				""");
		List<Object> parameters = new ArrayList<>();
		parameters.add(user.getId());
		if (!selectedAccountIds.isEmpty()) {
			sql.append(" and t.trading_account_id in (");
			for (int i = 0; i < selectedAccountIds.size(); i++) {
				if (i > 0) {
					sql.append(", ");
				}
				sql.append('?');
				parameters.add(selectedAccountIds.get(i));
			}
			sql.append(')');
		}
		if (symbols != null && !symbols.isEmpty()) {
			List<String> selectedSymbols = symbols.stream()
					.map(symbol -> symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT))
					.distinct()
					.toList();
			if (selectedSymbols.stream().anyMatch(symbol -> symbol.isBlank() || symbol.length() > 32)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Symbols must contain 1 to 32 characters.");
			}
			sql.append(" and t.symbol in (")
					.append(String.join(", ", java.util.Collections.nCopies(selectedSymbols.size(), "?")))
					.append(')');
			parameters.addAll(selectedSymbols);
		}
		var query = jdbcClient.sql(sql.toString());
		for (int i = 0; i < parameters.size(); i++) {
			query = query.param(i + 1, parameters.get(i));
		}
		return query.query((rs, rowNumber) -> new AccountSummary(
				rs.getLong("trade_count"),
				rs.getBigDecimal("gross_pnl"),
				rs.getBigDecimal("fees_and_swap"),
				rs.getBigDecimal("net_pnl"))).single();
	}

	private List<Long> validateAccountIds(Long userId, List<Long> accountIds) {
		if (accountIds == null || accountIds.isEmpty()) {
			assertSingleCurrency(accounts.findAllByUserIdOrderById(userId));
			return List.of();
		}
		List<Long> selected = accountIds.stream().distinct().toList();
		List<TradingAccountEntity> owned = accounts.findAllByIdInAndUserId(selected, userId);
		if (owned.size() != selected.size()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "One or more trading accounts were not found.");
		}
		assertSingleCurrency(owned);
		return selected;
	}

	private void assertSingleCurrency(List<TradingAccountEntity> selectedAccounts) {
		if (selectedAccounts.stream().map(TradingAccountEntity::getCurrency).distinct().count() > 1) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST, "Statistics cannot combine accounts with different currencies.");
		}
	}

	public record AccountSummary(
			long tradeCount,
			BigDecimal grossPnl,
			BigDecimal feesAndSwap,
			BigDecimal netPnl) {
	}

}
