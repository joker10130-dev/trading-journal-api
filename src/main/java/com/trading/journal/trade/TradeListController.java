package com.trading.journal.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import com.trading.journal.account.CurrentUserService;
import com.trading.journal.account.TradingAccountRepository;
import com.trading.journal.account.UserEntity;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/trading-accounts/{accountId}/trades")
public class TradeListController {

	private static final int DEFAULT_PAGE_SIZE = 25;
	private static final int MAX_PAGE_SIZE = 100;
	private static final String SELECT_COLUMNS = """
			select t.id, t.trading_account_id, t.ticket, t.symbol, t.side, t.status,
			       t.opened_at, t.open_price, t.opened_volume, t.gross_pnl, t.fees_and_swap,
			       t.net_pnl, t.r_multiple, t.r_source, t.r_flag, t.has_trades_report,
			       t.has_tradingview_data, t.deleted_at,
			       (select max(c.closed_at) from trade_closes c where c.trade_id = t.id) as last_closed_at,
			       (select c.close_price from trade_closes c where c.trade_id = t.id order by c.closed_at desc nulls last, c.id desc limit 1) as last_close_price,
			       (select count(*) from trade_closes c where c.trade_id = t.id) as close_count
			from trades t
			""";

	private final CurrentUserService currentUserService;
	private final TradingAccountRepository accounts;
	private final JdbcClient jdbcClient;

	public TradeListController(
			CurrentUserService currentUserService,
			TradingAccountRepository accounts,
			JdbcClient jdbcClient) {
		this.currentUserService = currentUserService;
		this.accounts = accounts;
		this.jdbcClient = jdbcClient;
	}

	@GetMapping
	public TradePage list(
			@PathVariable Long accountId,
			@RequestParam(required = false) String symbol,
			@RequestParam(required = false) LocalDate from,
			@RequestParam(required = false) LocalDate to,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		UserEntity user = requireOwnedAccount(accountId);
		validatePage(page, size);
		if (from != null && to != null && from.isAfter(to)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The start date must not be after the end date.");
		}
		ZoneId zone = ZoneId.of(user.getTimezone());
		Instant fromAt = from == null ? null : from.atStartOfDay(zone).toInstant();
		Instant toExclusive = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
		String normalizedSymbol = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase(Locale.ROOT);
		String filter = buildFilter(normalizedSymbol, fromAt, toExclusive, false);
		long totalElements = count(user.getId(), accountId, filter, normalizedSymbol, fromAt, toExclusive);
		List<TradeListItem> content = fetchPage(
				user.getId(), accountId, filter, normalizedSymbol, fromAt, toExclusive, page, size);
		return new TradePage(content, page, size, totalElements, pageCount(totalElements, size));
	}

	@GetMapping("/deleted")
	public TradePage deleted(
			@PathVariable Long accountId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		UserEntity user = requireOwnedAccount(accountId);
		validatePage(page, size);
		String filter = buildFilter(null, null, null, true);
		long totalElements = count(user.getId(), accountId, filter, null, null, null);
		List<TradeListItem> content = fetchPage(user.getId(), accountId, filter, null, null, null, page, size);
		return new TradePage(content, page, size, totalElements, pageCount(totalElements, size));
	}

	private UserEntity requireOwnedAccount(Long accountId) {
		UserEntity user = currentUserService.requireCurrentUser();
		if (accounts.findByIdAndUserId(accountId, user.getId()).isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Trading account was not found.");
		}
		return user;
	}

	private void validatePage(int page, int size) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST, "Page must be non-negative and size must be between 1 and 100.");
		}
	}

	private String buildFilter(String symbol, Instant fromAt, Instant toExclusive, boolean deleted) {
		StringBuilder filter = new StringBuilder(deleted ? " where t.deleted_at is not null" : " where t.deleted_at is null");
		if (symbol != null) {
			filter.append(" and t.symbol = :symbol");
		}
		if (fromAt != null || toExclusive != null) {
			filter.append(" and exists (select 1 from trade_closes c where c.trade_id = t.id");
			if (fromAt != null) filter.append(" and c.closed_at >= :fromAt");
			if (toExclusive != null) filter.append(" and c.closed_at < :toExclusive");
			filter.append(')');
		}
		filter.append(" and t.user_id = :userId and t.trading_account_id = :accountId");
		return filter.toString();
	}

	private long count(
			Long userId,
			Long accountId,
			String filter,
			String symbol,
			Instant fromAt,
			Instant toExclusive) {
		var query = jdbcClient.sql("select count(*) from trades t" + filter)
				.param("userId", userId)
				.param("accountId", accountId);
		if (symbol != null) query = query.param("symbol", symbol);
		if (fromAt != null) query = query.param("fromAt", OffsetDateTime.ofInstant(fromAt, ZoneOffset.UTC));
		if (toExclusive != null) query = query.param("toExclusive", OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC));
		return query.query(Long.class).single();
	}

	private List<TradeListItem> fetchPage(
			Long userId,
			Long accountId,
			String filter,
			String symbol,
			Instant fromAt,
			Instant toExclusive,
			int page,
			int size) {
		var query = jdbcClient.sql(SELECT_COLUMNS + filter + " order by last_closed_at desc nulls last, t.id desc limit :limit offset :offset")
				.param("userId", userId)
				.param("accountId", accountId)
				.param("limit", size)
				.param("offset", Math.multiplyExact((long) page, size));
		if (symbol != null) query = query.param("symbol", symbol);
		if (fromAt != null) query = query.param("fromAt", OffsetDateTime.ofInstant(fromAt, ZoneOffset.UTC));
		if (toExclusive != null) query = query.param("toExclusive", OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC));
		return query.query((rs, row) -> new TradeListItem(
				rs.getLong("id"),
				rs.getLong("trading_account_id"),
				rs.getString("ticket"),
				rs.getString("symbol"),
				rs.getString("side"),
				rs.getString("status"),
				instant(rs.getObject("opened_at", OffsetDateTime.class)),
				instant(rs.getObject("last_closed_at", OffsetDateTime.class)),
				instant(rs.getObject("deleted_at", OffsetDateTime.class)),
				rs.getBigDecimal("open_price"),
				rs.getBigDecimal("last_close_price"),
				rs.getBigDecimal("opened_volume"),
				rs.getBigDecimal("gross_pnl"),
				rs.getBigDecimal("fees_and_swap"),
				rs.getBigDecimal("net_pnl"),
				rs.getBigDecimal("r_multiple"),
				rs.getString("r_source"),
				rs.getString("r_flag"),
				rs.getBoolean("has_trades_report"),
				rs.getBoolean("has_tradingview_data"),
				rs.getLong("close_count"))).list();
	}

	private Instant instant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	private int pageCount(long totalElements, int size) {
		return (int) ((totalElements + size - 1) / size);
	}

	public record TradePage(List<TradeListItem> content, int page, int size, long totalElements, int totalPages) {
	}

	public record TradeListItem(
			Long id,
			Long tradingAccountId,
			String ticket,
			String symbol,
			String side,
			String status,
			Instant openedAt,
			Instant closedAt,
			Instant deletedAt,
			BigDecimal openPrice,
			BigDecimal closePrice,
			BigDecimal volume,
			BigDecimal grossPnl,
			BigDecimal feesAndSwap,
			BigDecimal netPnl,
			BigDecimal rMultiple,
			String rSource,
			String rFlag,
			boolean hasTradesReport,
			boolean hasTradingViewData,
			long closeCount) {
	}
}
