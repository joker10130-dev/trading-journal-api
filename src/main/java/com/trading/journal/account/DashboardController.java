package com.trading.journal.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/trading-accounts/summary")
public class DashboardController {

	private final CurrentUserService currentUserService;
	private final TradingAccountRepository accounts;
	private final JdbcClient jdbcClient;

	public DashboardController(
			CurrentUserService currentUserService,
			TradingAccountRepository accounts,
			JdbcClient jdbcClient) {
		this.currentUserService = currentUserService;
		this.accounts = accounts;
		this.jdbcClient = jdbcClient;
	}

	@GetMapping("/dashboard")
	public DashboardSummary dashboard(
			@RequestParam(defaultValue = "THIS_MONTH") Period period,
			@RequestParam(required = false) List<Long> accountIds,
			@RequestParam(required = false) List<String> symbols) {
		UserEntity user = currentUserService.requireCurrentUser();
		ZoneId timezone = ZoneId.of(user.getTimezone());
		PeriodRange range = period.range(LocalDate.now(timezone));
		List<Long> selectedAccountIds = validateAccountIds(user.getId(), accountIds);
		List<String> selectedSymbols = normalizeSymbols(symbols);
		QueryFilter filter = new QueryFilter(user.getId(), selectedAccountIds, selectedSymbols, range);

		List<DailyTotal> daily = dailyTotals(filter);
		OverallTotal totals = overallTotals(filter);
		WinCounts winCounts = winCounts(filter);
		EmotionMeter emotionMeter = emotionMeter(filter);
		List<DailyPoint> cumulative = cumulative(daily);
		int dailyStreak = dailyStreak(daily);
		LocalDate effectiveStart = range.startDate() == null
				? daily.stream().map(DailyTotal::date).findFirst().orElse(range.endDate())
				: range.startDate();

		return new DashboardSummary(
				period,
				effectiveStart,
				range.endDate(),
				totals.tradeCount(),
				totals.tradesWithR(),
				totals.tradeCount() - totals.tradesWithR(),
				totals.flaggedTradeCount(),
				totals.netPnl(),
				totals.totalR(),
				winCounts.wins(),
				winCounts.smalls(),
				winCounts.losses(),
				dailyStreak,
				daily,
				cumulative,
				weeklyTotals(daily, effectiveStart, range.endDate()),
				availableSymbols(user.getId(), selectedAccountIds),
				emotionMeter);
	}

	private EmotionMeter emotionMeter(QueryFilter filter) {
		EmotionCounts counts = jdbcClient.sql("""
						select count(distinct t.id) filter (where exists (
						           select 1 from trade_tag_mappings m
						           join tags g on g.id = m.tag_id and g.user_id = m.user_id
						           where m.trade_id = t.id and m.user_id = t.user_id
						             and g.category = 'EMOTION' and g.sentiment = 'POSITIVE'
						       )) as positive_trade_count,
						       count(distinct t.id) filter (where exists (
						           select 1 from trade_tag_mappings m
						           join tags g on g.id = m.tag_id and g.user_id = m.user_id
						           where m.trade_id = t.id and m.user_id = t.user_id
						             and g.category = 'EMOTION'
						       )) as emotion_trade_count
						""" + filter.fromAndWhere())
				.params(filter.parameters())
				.query((rs, rowNum) -> new EmotionCounts(
						rs.getLong("positive_trade_count"),
						rs.getLong("emotion_trade_count")))
				.single();
		BigDecimal percentage = counts.emotionTradeCount() == 0
				? BigDecimal.ZERO
				: BigDecimal.valueOf(counts.positiveTradeCount())
						.multiply(BigDecimal.valueOf(100))
						.divide(BigDecimal.valueOf(counts.emotionTradeCount()), 1, RoundingMode.HALF_UP);
		return new EmotionMeter(counts.positiveTradeCount(), counts.emotionTradeCount(), percentage);
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

	private List<String> normalizeSymbols(List<String> symbols) {
		if (symbols == null || symbols.isEmpty()) {
			return List.of();
		}
		List<String> selected = symbols.stream()
				.map(symbol -> symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT))
				.distinct()
				.toList();
		if (selected.stream().anyMatch(symbol -> symbol.isBlank() || symbol.length() > 32)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Symbols must contain 1 to 32 characters.");
		}
		return selected;
	}

	private List<DailyTotal> dailyTotals(QueryFilter filter) {
		return jdbcClient.sql("""
						select (c.closed_at at time zone u.timezone)::date as report_date,
						       count(distinct c.trade_id) as trade_count,
						       count(distinct c.trade_id) filter (where c.r_multiple is not null) as trades_with_r,
						       coalesce(sum(c.net_pnl), 0) as net_pnl,
						       coalesce(sum(c.r_multiple), 0) as total_r
						""" + filter.fromAndWhere() + """
						group by report_date
						order by report_date
						""")
				.params(filter.parameters())
				.query((rs, rowNum) -> new DailyTotal(
						rs.getObject("report_date", LocalDate.class),
						rs.getLong("trade_count"),
						rs.getLong("trades_with_r"),
						rs.getBigDecimal("net_pnl"),
						rs.getBigDecimal("total_r")))
				.list();
	}

	private OverallTotal overallTotals(QueryFilter filter) {
		return jdbcClient.sql("""
						select count(distinct c.trade_id) as trade_count,
						       count(distinct c.trade_id) filter (where c.r_multiple is not null) as trades_with_r,
						       count(distinct t.id) filter (where t.r_flag = 'R_ABSOLUTE_OVER_5') as flagged_trade_count,
						       coalesce(sum(c.net_pnl), 0) as net_pnl,
						       coalesce(sum(c.r_multiple), 0) as total_r
						""" + filter.fromAndWhere())
				.params(filter.parameters())
				.query((rs, rowNum) -> new OverallTotal(
						rs.getLong("trade_count"),
						rs.getLong("trades_with_r"),
						rs.getLong("flagged_trade_count"),
						rs.getBigDecimal("net_pnl"),
						rs.getBigDecimal("total_r")))
				.single();
	}

	private WinCounts winCounts(QueryFilter filter) {
		return jdbcClient.sql("""
						select count(*) filter (where total_r > 1) as wins,
						       count(*) filter (where total_r > 0 and total_r <= 1) as smalls,
						       count(*) filter (where total_r <= 0) as losses
						from (
						    select c.trade_id, sum(c.r_multiple) as total_r
						    """ + filter.fromAndWhere() + """
						    and c.r_multiple is not null
						    group by c.trade_id
						    having count(c.r_multiple) > 0
						) trade_results
						""")
				.params(filter.parameters())
				.query((rs, rowNum) -> new WinCounts(
						rs.getLong("wins"),
						rs.getLong("smalls"),
						rs.getLong("losses")))
				.single();
	}

	private List<String> availableSymbols(Long userId, List<Long> accountIds) {
		StringBuilder sql = new StringBuilder("""
				select distinct t.symbol
				from trades t
				where t.user_id = ? and t.deleted_at is null
				""");
		List<Object> parameters = new ArrayList<>();
		parameters.add(userId);
		appendInFilter(sql, parameters, "t.trading_account_id", accountIds);
		sql.append(" order by t.symbol");
		return jdbcClient.sql(sql.toString())
				.params(parameters.toArray())
				.query(String.class)
				.list();
	}

	private List<DailyPoint> cumulative(List<DailyTotal> daily) {
		BigDecimal total = BigDecimal.ZERO;
		List<DailyPoint> points = new ArrayList<>(daily.size());
		for (DailyTotal day : daily) {
			total = total.add(day.netPnl());
			points.add(new DailyPoint(day.date(), total));
		}
		return List.copyOf(points);
	}

	private int dailyStreak(List<DailyTotal> daily) {
		int streak = 0;
		for (int index = daily.size() - 1; index >= 0; index--) {
			DailyTotal day = daily.get(index);
			if (day.tradesWithR() == 0) {
				continue;
			}
			if (day.totalR().signum() <= 0) {
				break;
			}
			streak++;
		}
		return streak;
	}

	private List<WeeklyTotal> weeklyTotals(List<DailyTotal> daily, LocalDate start, LocalDate end) {
		Map<LocalDate, DailyTotal> dailyByDate = new HashMap<>();
		for (DailyTotal day : daily) {
			dailyByDate.put(day.date(), day);
		}

		List<WeeklyTotal> weeks = new ArrayList<>();
		YearMonth month = YearMonth.from(start);
		YearMonth lastMonth = YearMonth.from(end);
		while (!month.isAfter(lastMonth)) {
			LocalDate monthStart = month.atDay(1);
			LocalDate monthEnd = month.atEndOfMonth();
			LocalDate weekStart = monthStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
			while (!weekStart.isAfter(monthEnd)) {
				LocalDate weekEnd = weekStart.plusDays(6);
				LocalDate segmentStart = laterOf(monthStart, weekStart);
				LocalDate segmentEnd = earlierOf(monthEnd, weekEnd);
				LocalDate selectedStart = laterOf(start, segmentStart);
				LocalDate selectedEnd = earlierOf(end, segmentEnd);
				if (!selectedStart.isAfter(selectedEnd)) {
					long tradeCount = 0;
					BigDecimal netPnl = BigDecimal.ZERO;
					for (LocalDate day = selectedStart; !day.isAfter(selectedEnd); day = day.plusDays(1)) {
						DailyTotal total = dailyByDate.get(day);
						if (total != null) {
							tradeCount += total.tradeCount();
							netPnl = netPnl.add(total.netPnl());
						}
					}
					weeks.add(new WeeklyTotal(segmentStart, segmentEnd, tradeCount, netPnl));
				}
				weekStart = weekStart.plusWeeks(1);
			}
			month = month.plusMonths(1);
		}
		return List.copyOf(weeks);
	}

	private LocalDate laterOf(LocalDate left, LocalDate right) {
		return left.isAfter(right) ? left : right;
	}

	private LocalDate earlierOf(LocalDate left, LocalDate right) {
		return left.isBefore(right) ? left : right;
	}

	private static void appendInFilter(StringBuilder sql, List<Object> parameters, String column, List<?> values) {
		appendInClause(sql, column, values);
		parameters.addAll(values);
	}

	private static void appendInClause(StringBuilder sql, String column, List<?> values) {
		if (values.isEmpty()) {
			return;
		}
		sql.append(" and ").append(column).append(" in (")
				.append(String.join(", ", java.util.Collections.nCopies(values.size(), "?")))
				.append(')');
	}

	public enum Period {
		THIS_MONTH,
		LAST_MONTH,
		LAST_QUARTER,
		LAST_YEAR,
		ETERNAL;

		private PeriodRange range(LocalDate today) {
			YearMonth currentMonth = YearMonth.from(today);
			return switch (this) {
				case THIS_MONTH -> new PeriodRange(currentMonth.atDay(1), today);
				case LAST_MONTH -> {
					YearMonth lastMonth = currentMonth.minusMonths(1);
					yield new PeriodRange(lastMonth.atDay(1), lastMonth.atEndOfMonth());
				}
				case LAST_QUARTER -> new PeriodRange(today.minusMonths(3), today);
				case LAST_YEAR -> new PeriodRange(today.minusYears(1), today);
				case ETERNAL -> new PeriodRange(null, today);
			};
		}
	}

	private record PeriodRange(LocalDate startDate, LocalDate endDate) {
	}

	private record QueryFilter(
			Long userId,
			List<Long> accountIds,
			List<String> symbols,
			PeriodRange range) {

		private String fromAndWhere() {
			StringBuilder sql = new StringBuilder("""
					from trade_closes c
					join trades t on t.id = c.trade_id and t.user_id = c.user_id and t.deleted_at is null
					join users u on u.id = c.user_id
					where c.user_id = ? and c.closed_at is not null
					  and (c.closed_at at time zone u.timezone)::date <= ?
					""");
			if (range.startDate() != null) {
				sql.append(" and (c.closed_at at time zone u.timezone)::date >= ?");
			}
			appendInClause(sql, "c.trading_account_id", accountIds);
			appendInClause(sql, "t.symbol", symbols);
			return sql.toString();
		}

		private Object[] parameters() {
			List<Object> parameters = new ArrayList<>();
			parameters.add(userId);
			parameters.add(range.endDate());
			if (range.startDate() != null) {
				parameters.add(range.startDate());
			}
			parameters.addAll(accountIds);
			parameters.addAll(symbols);
			return parameters.toArray();
		}
	}

	private record OverallTotal(
			long tradeCount,
			long tradesWithR,
			long flaggedTradeCount,
			BigDecimal netPnl,
			BigDecimal totalR) {
	}

	private record WinCounts(long wins, long smalls, long losses) {
	}

	private record EmotionCounts(long positiveTradeCount, long emotionTradeCount) {
	}

	public record DashboardSummary(
			Period period,
			LocalDate startDate,
			LocalDate endDate,
			long tradeCount,
			long tradesWithR,
			long tradesWithoutR,
			long flaggedTradeCount,
			BigDecimal netPnl,
			BigDecimal totalR,
			long wins,
			long smalls,
			long losses,
			int dailyStreak,
			List<DailyTotal> daily,
			List<DailyPoint> cumulative,
			List<WeeklyTotal> weekly,
			List<String> availableSymbols,
			EmotionMeter emotionMeter) {
	}

	public record EmotionMeter(long positiveTradeCount, long emotionTradeCount, BigDecimal positivePercentage) {
	}

	public record DailyTotal(
			LocalDate date,
			long tradeCount,
			long tradesWithR,
			BigDecimal netPnl,
			BigDecimal totalR) {
	}

	public record DailyPoint(LocalDate date, BigDecimal cumulativeNetPnl) {
	}

	public record WeeklyTotal(LocalDate startDate, LocalDate endDate, long tradeCount, BigDecimal netPnl) {
	}
}
