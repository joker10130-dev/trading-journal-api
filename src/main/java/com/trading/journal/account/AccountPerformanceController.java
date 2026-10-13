package com.trading.journal.account;

import java.math.BigDecimal;
import java.time.LocalDate;
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
public class AccountPerformanceController {

	private final CurrentUserService currentUserService;
	private final TradingAccountRepository accounts;
	private final JdbcClient jdbcClient;

	public AccountPerformanceController(
			CurrentUserService currentUserService,
			TradingAccountRepository accounts,
			JdbcClient jdbcClient) {
		this.currentUserService = currentUserService;
		this.accounts = accounts;
		this.jdbcClient = jdbcClient;
	}

	@GetMapping("/summary/performance")
	public PerformanceSummary performance(@RequestParam(required = false) List<Long> accountIds) {
		UserEntity user = currentUserService.requireCurrentUser();
		List<Long> selectedAccountIds = validateAccountIds(user.getId(), accountIds);
		String filter = accountFilter(selectedAccountIds);
		List<Object> parameters = new ArrayList<>();
		parameters.add(user.getId());
		parameters.addAll(selectedAccountIds);

		OverallTotal overall = jdbcClient.sql("""
						select count(distinct c.trade_id) as trade_count,
						       count(distinct c.trade_id) filter (where c.r_multiple is not null) as trades_with_r,
						       count(*) filter (where c.closed_at is null) as undated_closes,
						       coalesce(sum(c.net_pnl), 0) as net_pnl,
						       coalesce(sum(c.r_multiple), 0) as total_r
						from trade_closes c
						where c.user_id = ?
						""" + filter)
				.params(parameters.toArray())
				.query((rs, rowNum) -> new OverallTotal(
						rs.getLong("trade_count"),
						rs.getLong("trades_with_r"),
						rs.getLong("undated_closes"),
						rs.getBigDecimal("net_pnl"),
						rs.getBigDecimal("total_r")))
				.single();

		List<DailyTotal> daily = jdbcClient.sql("""
						select (c.closed_at at time zone u.timezone)::date as report_date,
						       count(distinct c.trade_id) as trade_count,
						       count(distinct c.trade_id) filter (where c.r_multiple is not null) as trades_with_r,
						       coalesce(sum(c.net_pnl), 0) as net_pnl,
						       coalesce(sum(c.r_multiple), 0) as total_r
						from trade_closes c
						join users u on u.id = c.user_id
						where c.user_id = ? and c.closed_at is not null
						""" + filter + """
						group by report_date
						order by report_date
						""")
				.params(parameters.toArray())
				.query((rs, rowNum) -> new DailyTotal(
						rs.getObject("report_date", LocalDate.class),
						rs.getLong("trade_count"),
						rs.getLong("trades_with_r"),
						rs.getBigDecimal("net_pnl"),
						rs.getBigDecimal("total_r")))
				.list();

		List<MonthlyTotal> monthly = jdbcClient.sql("""
						select date_trunc('month', c.closed_at at time zone u.timezone)::date as report_month,
						       count(distinct c.trade_id) as trade_count,
						       count(distinct c.trade_id) filter (where c.r_multiple is not null) as trades_with_r,
						       coalesce(sum(c.net_pnl), 0) as net_pnl,
						       coalesce(sum(c.r_multiple), 0) as total_r
						from trade_closes c
						join users u on u.id = c.user_id
						where c.user_id = ? and c.closed_at is not null
						""" + filter + """
						group by report_month
						order by report_month
						""")
				.params(parameters.toArray())
				.query((rs, rowNum) -> new MonthlyTotal(
						rs.getObject("report_month", LocalDate.class),
						rs.getLong("trade_count"),
						rs.getLong("trades_with_r"),
						rs.getBigDecimal("net_pnl"),
						rs.getBigDecimal("total_r")))
				.list();

		return new PerformanceSummary(
				overall.netPnl(),
				overall.totalR(),
				overall.tradeCount(),
				overall.tradesWithR(),
				overall.tradeCount() - overall.tradesWithR(),
				overall.undatedCloses(),
				daily,
				monthly);
	}

	private List<Long> validateAccountIds(Long userId, List<Long> accountIds) {
		if (accountIds == null || accountIds.isEmpty()) {
			return List.of();
		}
		List<Long> selected = accountIds.stream().distinct().toList();
		if (accounts.findAllByIdInAndUserId(selected, userId).size() != selected.size()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "One or more trading accounts were not found.");
		}
		return selected;
	}

	private String accountFilter(List<Long> accountIds) {
		if (accountIds.isEmpty()) {
			return "";
		}
		return " and c.trading_account_id in (" + String.join(", ", java.util.Collections.nCopies(
				accountIds.size(), "?")) + ") ";
	}

	public record PerformanceSummary(
			BigDecimal netPnl,
			BigDecimal totalR,
			long tradeCount,
			long tradesWithR,
			long tradesWithoutR,
			long undatedCloses,
			List<DailyTotal> daily,
			List<MonthlyTotal> monthly) {
	}

	public record DailyTotal(
			LocalDate date,
			long tradeCount,
			long tradesWithR,
			BigDecimal netPnl,
			BigDecimal totalR) {
	}

	public record MonthlyTotal(
			LocalDate month,
			long tradeCount,
			long tradesWithR,
			BigDecimal netPnl,
			BigDecimal totalR) {
	}

	private record OverallTotal(
			long tradeCount,
			long tradesWithR,
			long undatedCloses,
			BigDecimal netPnl,
			BigDecimal totalR) {
	}

}
