package com.trading.journal.trade;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
