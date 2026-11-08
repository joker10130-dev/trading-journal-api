package com.trading.journal.trade;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import com.trading.journal.TestcontainersConfiguration;
import com.trading.journal.JwtTestProperties;
import com.trading.journal.tradingview.TradingViewOrder;
import com.trading.journal.tradingview.TradingViewReportReader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
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

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private TradingViewReportReader tradingViewReportReader;

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
				.andExpect(jsonPath("$.daily[0].totalR").value(1.6))
				.andExpect(jsonPath("$.monthly[0].month").value("2026-09-01"))
				.andExpect(jsonPath("$.monthly[0].netPnl").value(8))
				.andExpect(jsonPath("$.monthly[0].totalR").value(1.6));

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
	void manualTradeWithoutRIsExcludedFromDailyAndMonthlyRTotals() throws Exception {
		long accountId = createAccount(USER, "Manual without R");
		MvcResult created = mockMvc.perform(post("/api/v1/trading-accounts/{accountId}/trades", accountId)
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
						.with(user(USER)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.rMultiple").value(org.hamcrest.Matchers.nullValue()))
				.andReturn();
		long tradeId = objectMapper.readTree(created.getResponse().getContentAsByteArray()).path("id").asLong();

		org.junit.jupiter.api.Assertions.assertEquals(
				"NONE",
				jdbcClient.sql("select r_source from trades where id = ?")
						.param(tradeId)
						.query(String.class)
						.single());

		mockMvc.perform(get("/api/v1/trading-accounts/summary/performance")
						.param("accountIds", Long.toString(accountId))
						.with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tradesWithR").value(0))
				.andExpect(jsonPath("$.tradesWithoutR").value(1))
				.andExpect(jsonPath("$.totalR").value(0))
				.andExpect(jsonPath("$.daily.length()").value(1))
				.andExpect(jsonPath("$.daily[0].date").value("2026-09-01"))
				.andExpect(jsonPath("$.daily[0].tradesWithR").value(0))
				.andExpect(jsonPath("$.daily[0].totalR").value(0))
				.andExpect(jsonPath("$.monthly.length()").value(1))
				.andExpect(jsonPath("$.monthly[0].month").value("2026-09-01"))
				.andExpect(jsonPath("$.monthly[0].tradesWithR").value(0))
				.andExpect(jsonPath("$.monthly[0].totalR").value(0));
	}

	@Test
	void rejectsOpenAndPartiallyClosedManualTrades() throws Exception {
		long accountId = createAccount(USER, "Closed trades only");
		String baseRequest = """
				{
				  "symbol": "XAUUSD",
				  "side": "LONG",
				  "volume": 0.01,
				  "openedAt": "2026-09-01T09:00:00",
				  "closePrice": 4390,
				  "openPrice": 4380,
				  "profit": 10,
				  "swaps": 0,
				  "commission": 0
				}
				""";

		mockMvc.perform(post("/api/v1/trading-accounts/{accountId}/trades", accountId)
						.contentType(MediaType.APPLICATION_JSON)
						.content(baseRequest.replace(
								"\"closePrice\"", "\"closedAt\": null,\n  \"closePrice\""))
						.with(user(USER)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(
						"Manual trades must have a close time; open positions are not supported."));

		mockMvc.perform(post("/api/v1/trading-accounts/{accountId}/trades", accountId)
						.contentType(MediaType.APPLICATION_JSON)
						.content(baseRequest.replace(
								"\"closePrice\"",
								"\"closedAt\": \"2026-09-01T10:00:00\",\n  \"status\": \"OPEN\",\n  \"closePrice\""))
						.with(user(USER)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(
						"Manual trades must be closed; open positions are not supported."));

		mockMvc.perform(post("/api/v1/trading-accounts/{accountId}/trades", accountId)
						.contentType(MediaType.APPLICATION_JSON)
						.content(baseRequest.replace(
								"\"closePrice\"",
								"\"closedAt\": \"2026-09-01T10:00:00\",\n  \"closes\": [{}, {}],\n  \"closePrice\""))
						.with(user(USER)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(
						"Manual trades accept one top-level close; partial close lists are not supported."));
	}

	@Test
	void mergesManualTradeByTicketAndPreservesRAcrossRealOct10Import() throws Exception {
		String reportPath = System.getenv("TRADINGVIEW_OCT_10_FILE");
		Assumptions.assumeTrue(reportPath != null && !reportPath.isBlank());

		Path path = Path.of(reportPath);
		byte[] reportBytes = Files.readAllBytes(path);
		String filename = path.getFileName().toString();
		var parsedReport = tradingViewReportReader.read(filename, reportBytes);
		TradingViewOrder matchedClose = parsedReport.orders().stream()
				.filter(order -> order.ticket() != null && order.hasClosedPnl())
				.filter(order -> parsedReport.orders().stream().anyMatch(opening ->
						order.ticket().equals(opening.ticket())
								&& order.ticket().equals(opening.orderId())
								&& opening.isFilled()
								&& opening.averageFillPrice() != null
								&& opening.filledQuantity() != null))
				.findFirst()
				.orElseThrow();
		String ticket = matchedClose.ticket();
		TradingViewOrder openingOrder = parsedReport.orders().stream()
				.filter(order -> ticket.equals(order.ticket()) && ticket.equals(order.orderId()) && order.isFilled())
				.findFirst()
				.orElseThrow();
		long accountId = createAccount(USER, "Real October import");
		String manualTrade = objectMapper.writeValueAsString(java.util.Map.ofEntries(
				java.util.Map.entry("ticket", ticket),
				java.util.Map.entry("symbol", openingOrder.symbol()),
				java.util.Map.entry("side", "BUY".equals(openingOrder.side()) ? "LONG" : "SHORT"),
				java.util.Map.entry("volume", openingOrder.filledQuantity()),
				java.util.Map.entry("openedAt", "2026-10-10T09:00:00"),
				java.util.Map.entry("closedAt", "2026-10-10T10:00:00"),
				java.util.Map.entry("openPrice", java.math.BigDecimal.ONE),
				java.util.Map.entry("closePrice", new java.math.BigDecimal("2")),
				java.util.Map.entry("profit", new java.math.BigDecimal("123")),
				java.util.Map.entry("swaps", java.math.BigDecimal.ZERO),
				java.util.Map.entry("commission", java.math.BigDecimal.ZERO),
				java.util.Map.entry("rMultiple", new java.math.BigDecimal("2.5"))));
		MvcResult created = mockMvc.perform(post("/api/v1/trading-accounts/{accountId}/trades", accountId)
						.contentType(MediaType.APPLICATION_JSON)
						.content(manualTrade)
						.with(user(USER)))
				.andExpect(status().isCreated())
				.andReturn();
		long tradeId = objectMapper.readTree(created.getResponse().getContentAsByteArray()).path("id").asLong();
		MockMultipartFile file = new MockMultipartFile("file", filename, "text/csv", reportBytes);

		MvcResult preview = mockMvc.perform(multipart(
						"/api/v1/trading-accounts/{accountId}/imports/tradingview/preview", accountId)
						.file(file)
						.with(user(USER)))
				.andExpect(status().isOk())
				.andReturn();
		String sha256 = objectMapper.readTree(preview.getResponse().getContentAsByteArray())
				.path("fileSha256").asString();
		importTradingViewFile(accountId, file, sha256);

		JsonNode mergedTrade = tradeDetail(tradeId);
		org.junit.jupiter.api.Assertions.assertEquals(1, countTradesWithTicket(accountId, ticket));
		org.junit.jupiter.api.Assertions.assertEquals(openingOrder.symbol(), mergedTrade.path("symbol").asString());
		org.junit.jupiter.api.Assertions.assertEquals(
				openingOrder.averageFillPrice().doubleValue(), mergedTrade.path("openPrice").asDouble());
		org.junit.jupiter.api.Assertions.assertTrue(mergedTrade.path("hasTradingViewData").asBoolean());
		org.junit.jupiter.api.Assertions.assertEquals(2.5, mergedTrade.path("rMultiple").asDouble());
		org.junit.jupiter.api.Assertions.assertEquals("MANUAL", mergedTrade.path("rSource").asString());
		org.junit.jupiter.api.Assertions.assertTrue(mergedTrade.path("closes").size() > 0);
		org.junit.jupiter.api.Assertions.assertTrue(java.util.stream.StreamSupport.stream(
					mergedTrade.path("closes").spliterator(), false)
				.allMatch(close -> "TRADINGVIEW".equals(close.path("source").asString())));

		mockMvc.perform(put("/api/v1/trades/{tradeId}/r", tradeId)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"rMultiple\": -1.25}")
						.with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rSource").value("MANUAL"));
		MvcResult repeatPreview = mockMvc.perform(multipart(
						"/api/v1/trading-accounts/{accountId}/imports/tradingview/preview", accountId)
						.file(file)
						.with(user(USER)))
				.andExpect(status().isOk())
				.andReturn();
		String repeatSha256 = objectMapper.readTree(repeatPreview.getResponse().getContentAsByteArray())
				.path("fileSha256").asString();
		importTradingViewFile(accountId, file, repeatSha256);

		JsonNode repeatedTrade = tradeDetail(tradeId);
		org.junit.jupiter.api.Assertions.assertEquals(-1.25, repeatedTrade.path("rMultiple").asDouble());
		org.junit.jupiter.api.Assertions.assertEquals("MANUAL", repeatedTrade.path("rSource").asString());
		java.math.BigDecimal closeR = jdbcClient.sql(
					"select coalesce(sum(r_multiple), 0) from trade_closes where trade_id = ?")
				.param(tradeId)
				.query(java.math.BigDecimal.class)
				.single();
		org.junit.jupiter.api.Assertions.assertEquals(
				0, closeR.compareTo(new java.math.BigDecimal("-1.25")));

		MvcResult performance = mockMvc.perform(get("/api/v1/trading-accounts/summary/performance")
						.param("accountIds", Long.toString(accountId))
						.with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalR").value(-1.25))
				.andExpect(jsonPath("$.tradesWithR").value(1))
				.andReturn();
		JsonNode monthly = objectMapper.readTree(performance.getResponse().getContentAsByteArray()).path("monthly");
		java.math.BigDecimal monthlyR = java.math.BigDecimal.ZERO;
		for (JsonNode total : monthly) {
			monthlyR = monthlyR.add(total.path("totalR").decimalValue());
		}
		org.junit.jupiter.api.Assertions.assertEquals(
				0, monthlyR.compareTo(new java.math.BigDecimal("-1.25")));
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

	private void importTradingViewFile(long accountId, MockMultipartFile file, String sha256) throws Exception {
		mockMvc.perform(multipart("/api/v1/trading-accounts/{accountId}/imports/tradingview", accountId)
						.file(file)
						.param("sha256", sha256)
						.with(user(USER)))
				.andExpect(status().isOk());
	}

	private JsonNode tradeDetail(long tradeId) throws Exception {
		MvcResult result = mockMvc.perform(get("/api/v1/trades/{tradeId}", tradeId).with(user(USER)))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsByteArray());
	}

	private long countTradesWithTicket(long accountId, String ticket) {
		return jdbcClient.sql("select count(*) from trades where trading_account_id = ? and ticket = ?")
				.param(accountId)
				.param(ticket)
				.query(Long.class)
				.single();
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
