package com.trading.journal.account;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
	public AccountSummary summary(@RequestParam(required = false) List<Long> accountIds) {
		UserEntity user = currentUserService.requireCurrentUser();
		StringBuilder sql = new StringBuilder("""
				select count(*) as trade_count,
				       coalesce(sum(gross_pnl), 0) as gross_pnl,
				       coalesce(sum(fees_and_swap), 0) as fees_and_swap,
				       coalesce(sum(net_pnl), 0) as net_pnl
				from trades
				where user_id = ?
				""");
		List<Object> parameters = new ArrayList<>();
		parameters.add(user.getId());
		if (accountIds != null && !accountIds.isEmpty()) {
			List<TradingAccountEntity> ownedAccounts = accounts.findAllByIdInAndUserId(accountIds, user.getId());
			if (ownedAccounts.size() != accountIds.stream().distinct().count()) {
				throw new ResponseStatusException(HttpStatus.NOT_FOUND, "One or more trading accounts were not found.");
			}
			sql.append(" and trading_account_id in (");
			for (int i = 0; i < accountIds.size(); i++) {
				if (i > 0) {
					sql.append(", ");
				}
				sql.append('?');
				parameters.add(accountIds.get(i));
			}
			sql.append(')');
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

	public record AccountSummary(
			long tradeCount,
			BigDecimal grossPnl,
			BigDecimal feesAndSwap,
			BigDecimal netPnl) {
	}

}
