package com.trading.journal.account;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import com.trading.journal.JwtTestProperties;
import com.trading.journal.TestcontainersConfiguration;
import com.trading.journal.trade.TradeCloseEntity;
import com.trading.journal.trade.TradeCloseRepository;
import com.trading.journal.trade.TradeEntity;
import com.trading.journal.trade.TradeRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DashboardIntegrationTests extends JwtTestProperties {

	private static final String USER = "dashboard@example.test";
	private static final String OTHER_USER = "dashboard-other@example.test";
	private static final ZoneId USER_ZONE = ZoneId.of("Asia/Bangkok");
	private static final AtomicLong FINGERPRINT = new AtomicLong();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private TradingAccountRepository accounts;

	@Autowired
	private TradeRepository trades;

	@Autowired
	private TradeCloseRepository closes;

	@Autowired
	private ObjectMapper objectMapper;

	private UserEntity user;
	private TradingAccountEntity account;

	@BeforeEach
	void createTestAccounts() {
		user = users.findByEmail(USER).orElseGet(() -> users.saveAndFlush(new UserEntity(USER)));
		account = createAccount(user, "USD");
	}

	@Test
	void dashboardCombinesPartialClosesAndSkipsDaysWithoutRInUserTimezone() throws Exception {
		LocalDate today = LocalDate.now(USER_ZONE);
		LocalDate monthStart = today.withDayOfMonth(1);
		addTrade(account, "XAUUSD", today.minusDays(2), new BigDecimal("20.00"),
				new BigDecimal("1.20"), new BigDecimal("0.60"), new BigDecimal("0.60"));
		addTrade(account, "XAUUSD", today, new BigDecimal("10.00"), new BigDecimal("0.50"));
		addTrade(account, "BTCUSD", today.minusDays(1), new BigDecimal("-5.00"), null);
		addTrade(account, "XAUUSD", today.minusDays(3), new BigDecimal("-10.00"), new BigDecimal("-1.00"));
		addTrade(account, "XAUUSD", monthStart.minusDays(1), new BigDecimal("500.00"), new BigDecimal("4.00"));

		var result = mockMvc.perform(get("/api/v1/trading-accounts/summary/dashboard")
						.param("period", "THIS_MONTH")
						.param("accountIds", account.getId().toString())
						.with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.startDate").value(monthStart.toString()))
				.andExpect(jsonPath("$.endDate").value(today.toString()))
				.andExpect(jsonPath("$.tradeCount").value(4))
				.andExpect(jsonPath("$.tradesWithR").value(3))
				.andExpect(jsonPath("$.tradesWithoutR").value(1))
				.andExpect(jsonPath("$.netPnl").value(15))
				.andExpect(jsonPath("$.totalR").value(0.7))
				.andExpect(jsonPath("$.wins").value(1))
				.andExpect(jsonPath("$.smalls").value(1))
				.andExpect(jsonPath("$.losses").value(1))
				.andExpect(jsonPath("$.dailyStreak").value(2))
				.andExpect(jsonPath("$.cumulative[-1].cumulativeNetPnl").value(15))
				.andExpect(jsonPath("$.availableSymbols").isArray())
				.andReturn();
		JsonNode payload = objectMapper.readTree(result.getResponse().getContentAsByteArray());
		JsonNode todayDaily = null;
		JsonNode partialCloseDay = null;
		for (JsonNode day : payload.path("daily")) {
			if (today.toString().equals(day.path("date").asString())) todayDaily = day;
			if (today.minusDays(2).toString().equals(day.path("date").asString())) partialCloseDay = day;
		}
		org.junit.jupiter.api.Assertions.assertNotNull(todayDaily);
		org.junit.jupiter.api.Assertions.assertNotNull(partialCloseDay);
		org.junit.jupiter.api.Assertions.assertEquals(1, todayDaily.path("tradeCount").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(1, partialCloseDay.path("tradesWithR").asInt());
		long weeklyTrades = 0;
		BigDecimal weeklyNetPnl = BigDecimal.ZERO;
		for (JsonNode week : payload.path("weekly")) {
			weeklyTrades += week.path("tradeCount").longValue();
			weeklyNetPnl = weeklyNetPnl.add(week.path("netPnl").decimalValue());
		}
		org.junit.jupiter.api.Assertions.assertEquals(4, weeklyTrades);
		org.junit.jupiter.api.Assertions.assertEquals(0, weeklyNetPnl.compareTo(BigDecimal.valueOf(15)));
	}

	@Test
	void filtersAllStatisticsByAccountAndSymbolAndKeepsUsersIsolated() throws Exception {
		LocalDate today = LocalDate.now(USER_ZONE);
		addTrade(account, "XAUUSD", today, new BigDecimal("10.00"), new BigDecimal("2.00"));
		addTrade(account, "BTCUSD", today, new BigDecimal("-5.00"), new BigDecimal("-1.00"));
		TradingAccountEntity otherAccount = createAccount(user, "USD");
		addTrade(otherAccount, "XAUUSD", today, new BigDecimal("100.00"), new BigDecimal("3.00"));
		UserEntity otherUser = users.findByEmail(OTHER_USER)
				.orElseGet(() -> users.saveAndFlush(new UserEntity(OTHER_USER)));
		TradingAccountEntity foreignAccount = createAccount(otherUser, "USD");
		addTrade(foreignAccount, "XAUUSD", today, new BigDecimal("1000.00"), new BigDecimal("4.00"));

		mockMvc.perform(get("/api/v1/trading-accounts/summary/dashboard")
						.param("period", "THIS_MONTH")
						.param("accountIds", account.getId().toString())
						.param("symbols", "xauusd")
						.with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tradeCount").value(1))
				.andExpect(jsonPath("$.netPnl").value(10))
				.andExpect(jsonPath("$.totalR").value(2));

		mockMvc.perform(get("/api/v1/trading-accounts/summary")
						.param("accountIds", account.getId().toString())
						.param("symbols", "BTCUSD")
						.with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tradeCount").value(1))
				.andExpect(jsonPath("$.netPnl").value(-5));

		mockMvc.perform(get("/api/v1/trading-accounts/summary/performance")
						.param("accountIds", account.getId().toString())
						.param("symbols", "XAUUSD")
						.with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tradeCount").value(1))
				.andExpect(jsonPath("$.netPnl").value(10));

		mockMvc.perform(get("/api/v1/trading-accounts/summary/dashboard")
						.param("accountIds", foreignAccount.getId().toString())
						.with(user(USER)))
				.andExpect(status().isNotFound());
	}

	@Test
	void dateRangesUseRollingUserCalendarDatesAndRejectMixedCurrencies() throws Exception {
		LocalDate today = LocalDate.now(USER_ZONE);
		LocalDate lastMonth = today.withDayOfMonth(1).minusMonths(1);
		mockMvc.perform(get("/api/v1/trading-accounts/summary/dashboard")
						.param("period", "LAST_MONTH")
						.param("accountIds", account.getId().toString())
						.with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.startDate").value(lastMonth.toString()))
				.andExpect(jsonPath("$.endDate").value(lastMonth.withDayOfMonth(lastMonth.lengthOfMonth()).toString()));

		mockMvc.perform(get("/api/v1/trading-accounts/summary/dashboard")
						.param("period", "LAST_QUARTER")
						.param("accountIds", account.getId().toString())
						.with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.startDate").value(today.minusMonths(3).toString()))
				.andExpect(jsonPath("$.endDate").value(today.toString()));

		mockMvc.perform(get("/api/v1/trading-accounts/summary/dashboard")
						.param("period", "LAST_YEAR")
						.param("accountIds", account.getId().toString())
						.with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.startDate").value(today.minusYears(1).toString()))
				.andExpect(jsonPath("$.endDate").value(today.toString()));

		TradingAccountEntity eurAccount = createAccount(user, "EUR");
		mockMvc.perform(get("/api/v1/trading-accounts/summary/dashboard")
						.param("accountIds", account.getId().toString(), eurAccount.getId().toString())
						.with(user(USER)))
				.andExpect(status().isBadRequest());
	}

	private TradingAccountEntity createAccount(UserEntity owner, String currency) {
		return accounts.saveAndFlush(new TradingAccountEntity(
				owner.getId(),
				"Dashboard " + FINGERPRINT.incrementAndGet(),
				"Eightcap",
				currency,
				null,
				(short) 180));
	}

	private void addTrade(
			TradingAccountEntity tradingAccount,
			String symbol,
			LocalDate closeDate,
			BigDecimal netPnl,
			BigDecimal rMultiple,
			BigDecimal... closeRValues) {
		List<BigDecimal> rValues = closeRValues.length == 0
				? java.util.Collections.singletonList(rMultiple)
				: List.of(closeRValues);
		Instant closedAt = closeDate.atTime(0, 30).atZone(USER_ZONE).toInstant();
		TradeEntity trade = new TradeEntity(
				tradingAccount.getUserId(),
				tradingAccount.getId(),
				null,
				symbol,
				"LONG",
				closedAt.minusSeconds(3600),
				new BigDecimal("100"),
				BigDecimal.valueOf(rValues.size()),
				netPnl,
				BigDecimal.ZERO,
				netPnl,
				rMultiple);
		trades.saveAndFlush(trade);

		BigDecimal closePnl = netPnl.divide(BigDecimal.valueOf(rValues.size()), 2, java.math.RoundingMode.HALF_UP);
		for (BigDecimal closeR : rValues) {
			Instant closeTime = closedAt;
			TradeCloseEntity close = new TradeCloseEntity(
					tradingAccount.getUserId(),
					tradingAccount.getId(),
					trade.getId(),
					null,
					fingerprint(),
					BigDecimal.ONE,
					LocalDateTime.ofInstant(closeTime, USER_ZONE),
					closeTime,
					new BigDecimal("101"),
					closePnl,
					BigDecimal.ZERO,
					BigDecimal.ZERO,
					closePnl);
			close.updateRMultiple(closeR);
			closes.saveAndFlush(close);
		}
	}

	private String fingerprint() {
		return "%064d".formatted(FINGERPRINT.incrementAndGet());
	}
}
