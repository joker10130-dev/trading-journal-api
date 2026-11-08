package com.trading.journal.trade;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trading.journal.TestcontainersConfiguration;
import com.trading.journal.JwtTestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ManualTradeIntegrationTests extends JwtTestProperties {

	private static final String USER = "manual-trade@example.test";

	@Autowired
	private MockMvc mockMvc;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void createsManualTradeAndIncludesItsCloseInPerformanceSummaries() throws Exception {
		long accountId = createAccount(USER, "Manual");

		MvcResult created = mockMvc.perform(post("/api/v1/trading-accounts/{accountId}/trades", accountId)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "symbol": "xauusd",
								  "side": "long",
								  "volume": 0.01,
								  "openedAt": "2026-09-01T09:00:00",
								  "closedAt": "2026-09-01T10:00:00",
								  "openPrice": 4380,
								  "closePrice": 4390,
								  "profit": 10,
								  "swaps": -1,
								  "commission": -1,
								  "rMultiple": 1.6
								}
								""")
						.with(user(USER)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.ticket").doesNotExist())
				.andExpect(jsonPath("$.symbol").value("XAUUSD"))
				.andExpect(jsonPath("$.side").value("LONG"))
				.andExpect(jsonPath("$.netPnl").value(8))
				.andExpect(jsonPath("$.rMultiple").value(1.6))
				.andExpect(jsonPath("$.rFlag").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.warnings").isEmpty())
				.andReturn();
		JsonNode createdTrade = objectMapper.readTree(created.getResponse().getContentAsByteArray());

		mockMvc.perform(get("/api/v1/trading-accounts/summary")
						.param("accountIds", Long.toString(accountId))
						.with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tradeCount").value(1))
				.andExpect(jsonPath("$.grossPnl").value(10))
				.andExpect(jsonPath("$.feesAndSwap").value(-2))
				.andExpect(jsonPath("$.netPnl").value(8));

		mockMvc.perform(get("/api/v1/trading-accounts/summary/performance")
						.param("accountIds", Long.toString(accountId))
						.with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tradeCount").value(1))
				.andExpect(jsonPath("$.tradesWithR").value(1))
				.andExpect(jsonPath("$.totalR").value(1.6))
				.andExpect(jsonPath("$.daily[0].date").value("2026-09-01"))
				.andExpect(jsonPath("$.daily[0].netPnl").value(8))
				.andExpect(jsonPath("$.daily[0].totalR").value(1.6));

		mockMvc.perform(post("/api/v1/trading-accounts/{accountId}/trades", accountId)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "symbol": "XAUUSD",
								  "side": "LONG",
								  "volume": 0.01,
								  "openedAt": "2026-09-01T11:00:00",
								  "closedAt": "2026-09-01T10:00:00",
								  "openPrice": 4380,
								  "closePrice": 4390,
								  "profit": 10,
								  "swaps": 0,
								  "commission": 0
								}
								""")
						.with(user(USER)))
				.andExpect(status().isBadRequest());

		org.junit.jupiter.api.Assertions.assertTrue(createdTrade.path("id").isNumber());
	}

	@Test
	void rejectsManualTradesForAnotherUsersAccount() throws Exception {
		long accountId = createAccount(USER, "Owned");
		String otherUser = "manual-trade-other@example.test";
		createAccount(otherUser, "Other");

		mockMvc.perform(post("/api/v1/trading-accounts/{accountId}/trades", accountId)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "symbol": "XAUUSD",
								  "side": "LONG",
								  "volume": 0.01,
								  "openedAt": "2026-09-01T09:00:00",
								  "closedAt": "2026-09-01T10:00:00",
								  "openPrice": 4380,
								  "closePrice": 4390,
								  "profit": 10,
								  "swaps": 0,
								  "commission": 0
								}
								""")
						.with(user(otherUser)))
				.andExpect(status().isNotFound());
	}

	@Test
	void listsEditsDeletesAndRestoresOwnedTrades() throws Exception {
		String email = "manual-trade-lifecycle@example.test";
		long accountId = createAccount(email, "Lifecycle");
		long tradeId = createManualTrade(accountId, email, "XAUUSD", "2026-09-01T10:00:00");
		createManualTrade(accountId, email, "EURUSD", "2026-09-02T10:00:00");

		mockMvc.perform(get("/api/v1/trading-accounts/{accountId}/trades", accountId)
					.param("from", "2026-09-01")
					.param("to", "2026-09-02")
					.param("page", "0")
					.param("size", "1")
					.with(user(email)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.totalPages").value(2))
			.andExpect(jsonPath("$.content[0].symbol").value("EURUSD"));

		mockMvc.perform(get("/api/v1/trades/{tradeId}", tradeId).with(user(email)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.rSource").value("MANUAL"))
			.andExpect(jsonPath("$.closes.length()").value(1))
			.andExpect(jsonPath("$.closes[0].source").value("MANUAL"));

		mockMvc.perform(put("/api/v1/trading-accounts/{accountId}/trades/{tradeId}", accountId, tradeId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "ticket": "EDITED-1",
							  "symbol": "BTCUSD",
							  "side": "SHORT",
							  "volume": 0.02,
							  "openedAt": "2026-09-01T09:00:00",
							  "closedAt": "2026-09-01T11:00:00",
							  "openPrice": 44000,
							  "closePrice": 43900,
							  "profit": 12,
							  "swaps": -1,
							  "commission": -1,
							  "rMultiple": -2
							}
							""")
					.with(user(email)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ticket").value("EDITED-1"))
			.andExpect(jsonPath("$.netPnl").value(10))
			.andExpect(jsonPath("$.rMultiple").value(-2));

		mockMvc.perform(get("/api/v1/trading-accounts/summary").param("accountIds", Long.toString(accountId)).with(user(email)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tradeCount").value(2))
			.andExpect(jsonPath("$.netPnl").value(20));

		mockMvc.perform(delete("/api/v1/trades/{tradeId}", tradeId).with(user(email)))
			.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/trades/{tradeId}", tradeId).with(user(email)))
			.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/trading-accounts/summary")
					.param("accountIds", Long.toString(accountId)).with(user(email)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tradeCount").value(1))
			.andExpect(jsonPath("$.netPnl").value(10));
		mockMvc.perform(get("/api/v1/trading-accounts/{accountId}/trades/deleted", accountId).with(user(email)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].deletedAt").isNotEmpty());

		mockMvc.perform(put("/api/v1/trades/{tradeId}/restore", tradeId).with(user(email)))
			.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/trades/{tradeId}", tradeId).with(user(email)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.netPnl").value(10));
	}

	private long createManualTrade(long accountId, String email, String symbol, String closedAt) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/trading-accounts/{accountId}/trades", accountId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "symbol": "%s",
							  "side": "LONG",
							  "volume": 0.01,
							  "openedAt": "2026-09-01T09:00:00",
							  "closedAt": "%s",
							  "openPrice": 4380,
							  "closePrice": 4390,
							  "profit": 12,
							  "swaps": -1,
							  "commission": -1,
							  "rMultiple": 1.5
							}
							""".formatted(symbol, closedAt))
					.with(user(email)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("id").asLong();
	}

	private long createAccount(String email, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/trading-accounts")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateAccountRequest(
								name, "Eightcap", "USD", null, null)))
						.with(user(email)))
				.andExpect(status().isCreated())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("id").asLong();
	}

	private record CreateAccountRequest(
			String name,
			String broker,
			String currency,
			String brokerLogin,
			Short tradesReportUtcOffsetMinutes) {
	}

}
