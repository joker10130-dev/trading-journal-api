package com.trading.journal.eightcap;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import com.trading.journal.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EightcapImportIntegrationTests {

	private static final String USER = "stage-one@example.test";
	private static byte[] fullXlsx;
	private static byte[] fullCsv;
	private static byte[] septemberCsv;

	@Autowired
	private MockMvc mockMvc;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	private JdbcClient jdbcClient;

	@BeforeAll
	static void createFixtures() throws Exception {
		List<List<String>> uniqueRows = EightcapFixture.uniqueRows();
		fullXlsx = EightcapFixture.xlsxBytes(uniqueRows, true);
		fullCsv = EightcapFixture.csvBytes(uniqueRows, true);
		septemberCsv = EightcapFixture.csvBytes(EightcapFixture.septemberRows(), false);
	}

	@Test
	void importsEightcapReportsAndPreservesAccountBoundaries() throws Exception {
		long accountA = createAccount("A", EightcapFixture.LOGIN);
		long accountB = createAccount("B", EightcapFixture.LOGIN);
		long csvAccount = createAccount("CSV", EightcapFixture.LOGIN);
		long septemberAccount = createAccount("September", EightcapFixture.LOGIN);
		long mismatchedAccount = createAccount("Mismatch", "other-synthetic-login");
		createAccount("Other user", EightcapFixture.LOGIN, "other@example.test");

		JsonNode xlsxPreview = preview(accountA, "eightcap.xlsx", fullXlsx);
		org.junit.jupiter.api.Assertions.assertEquals(EightcapFixture.TOTAL_ROWS, xlsxPreview.path("totalRows").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(EightcapFixture.DUPLICATE_ROWS, xlsxPreview.path("duplicateRows").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(181, xlsxPreview.path("uniqueRows").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(EightcapFixture.TRADE_ROWS, xlsxPreview.path("newTrades").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(EightcapFixture.CASH_ROWS, xlsxPreview.path("newCashTransactions").asInt());
		org.junit.jupiter.api.Assertions.assertFalse(xlsxPreview.path("loginMismatch").asBoolean());
		org.junit.jupiter.api.Assertions.assertEquals("HIGH_DUPLICATE_RATE", xlsxPreview.path("warnings").get(0).path("code").asString());

		long batchesBeforeChangedFile = importBatchCount();
		importFile(accountA, "eightcap.xlsx", fullXlsx, "0".repeat(64), false, 409);
		org.junit.jupiter.api.Assertions.assertEquals(batchesBeforeChangedFile, importBatchCount());

		JsonNode firstImport = importFile(
				accountA, "eightcap.xlsx", fullXlsx, xlsxPreview.path("fileSha256").asString(), false, 200);
		org.junit.jupiter.api.Assertions.assertEquals(176, firstImport.path("importedTradeCloses").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(5, firstImport.path("importedCashTransactions").asInt());
		assertSummary(List.of(accountA), 176, "-52.14", "10.21", "-41.93");

		JsonNode repeatPreview = preview(accountA, "eightcap.xlsx", fullXlsx);
		org.junit.jupiter.api.Assertions.assertEquals(0, repeatPreview.path("newTrades").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(176, repeatPreview.path("existingTrades").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(176, repeatPreview.path("alreadyImportedTradeCloses").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(5, repeatPreview.path("alreadyImportedCashTransactions").asInt());
		JsonNode repeatedImport = importFile(
				accountA, "eightcap.xlsx", fullXlsx, repeatPreview.path("fileSha256").asString(), false, 200);
		org.junit.jupiter.api.Assertions.assertEquals(0, repeatedImport.path("importedTradeCloses").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(0, repeatedImport.path("importedCashTransactions").asInt());
		assertSummary(List.of(accountA), 176, "-52.14", "10.21", "-41.93");

		JsonNode bPreview = preview(accountB, "eightcap.xlsx", fullXlsx);
		importFile(accountB, "eightcap.xlsx", fullXlsx, bPreview.path("fileSha256").asString(), false, 200);
		assertSummary(List.of(accountA), 176, "-52.14", "10.21", "-41.93");
		assertSummary(List.of(accountB), 176, "-52.14", "10.21", "-41.93");
		assertSummary(List.of(accountA, accountB), 352, "-104.28", "20.42", "-83.86");

		JsonNode csvPreview = preview(csvAccount, "eightcap.csv", fullCsv);
		org.junit.jupiter.api.Assertions.assertEquals(8_299, csvPreview.path("totalRows").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(8_118, csvPreview.path("duplicateRows").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(176, csvPreview.path("newTrades").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(5, csvPreview.path("newCashTransactions").asInt());
		importFile(csvAccount, "eightcap.csv", fullCsv, csvPreview.path("fileSha256").asString(), false, 200);
		assertSummary(List.of(csvAccount), 176, "-52.14", "10.21", "-41.93");

		JsonNode septemberPreview = preview(septemberAccount, "september.csv", septemberCsv);
		org.junit.jupiter.api.Assertions.assertEquals(24, septemberPreview.path("totalRows").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(0, septemberPreview.path("duplicateRows").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(22, septemberPreview.path("newTrades").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(2, septemberPreview.path("newCashTransactions").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(0, septemberPreview.path("warnings").size());
		importFile(
				septemberAccount,
				"september.csv",
				septemberCsv,
				septemberPreview.path("fileSha256").asString(),
				false,
				200);
		assertSummary(List.of(septemberAccount), 22, "-647.78", "0.00", "-647.78");

		JsonNode overlapPreview = preview(accountA, "september.csv", septemberCsv);
		org.junit.jupiter.api.Assertions.assertEquals(0, overlapPreview.path("newTrades").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(22, overlapPreview.path("existingTrades").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(0, overlapPreview.path("newCashTransactions").asInt());
		JsonNode overlapImport = importFile(
				accountA,
				"september.csv",
				septemberCsv,
				overlapPreview.path("fileSha256").asString(),
				false,
				200);
		org.junit.jupiter.api.Assertions.assertEquals(0, overlapImport.path("importedTradeCloses").asInt());
		assertSummary(List.of(accountA), 176, "-52.14", "10.21", "-41.93");

		JsonNode mismatchPreview = preview(mismatchedAccount, "eightcap.xlsx", fullXlsx);
		org.junit.jupiter.api.Assertions.assertTrue(mismatchPreview.path("loginMismatch").asBoolean());
		org.junit.jupiter.api.Assertions.assertEquals(
				"LOGIN_MISMATCH", mismatchPreview.path("warnings").get(1).path("code").asString());
		long batchesBeforeRejectedConfirmation = importBatchCount();
		importFile(
				mismatchedAccount,
				"eightcap.xlsx",
				fullXlsx,
				mismatchPreview.path("fileSha256").asString(),
				false,
				409);
		org.junit.jupiter.api.Assertions.assertEquals(batchesBeforeRejectedConfirmation, importBatchCount());
		importFile(
				mismatchedAccount,
				"eightcap.xlsx",
				fullXlsx,
				mismatchPreview.path("fileSha256").asString(),
				true,
				200);

		mockMvc.perform(multipart(
						"/api/v1/trading-accounts/{id}/imports/eightcap/preview", accountA)
						.file(file("eightcap.xlsx", fullXlsx))
						.with(user("other@example.test")))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/trading-accounts/summary")
						.param("accountIds", Long.toString(accountA))
						.with(user("other@example.test")))
						.andExpect(status().isNotFound());

		assertTradingViewImports();
		assertMalformedReportDoesNotWrite(accountA);
	}

	@Test
	void summarizesImportedReportPnlAndRByUserTimezone() throws Exception {
		long accountId = createAccount(
				"Performance",
				EightcapFixture.LOGIN,
				USER,
				(short) 180,
				null,
				null);
		JsonNode preview = preview(accountId, "september.csv", septemberCsv);
		importFile(accountId, "september.csv", septemberCsv, preview.path("fileSha256").asString(), false, 200);
		mockMvc.perform(put("/api/v1/trading-accounts/{id}/risk-rule", accountId)
						.with(user(USER))
						.contentType("application/json")
						.content(objectMapper.writeValueAsBytes(
								new RiskRuleRequest("FIXED_AMOUNT", new BigDecimal("10")))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.defaultRiskMode").value("FIXED_AMOUNT"))
				.andExpect(jsonPath("$.defaultRiskValue").value(10));

		mockMvc.perform(get("/api/v1/trading-accounts/summary/performance")
						.param("accountIds", Long.toString(accountId))
						.with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.netPnl").value(-647.78))
				.andExpect(jsonPath("$.totalR").value(-64.778))
				.andExpect(jsonPath("$.tradeCount").value(22))
				.andExpect(jsonPath("$.tradesWithR").value(22))
				.andExpect(jsonPath("$.tradesWithoutR").value(0))
				.andExpect(jsonPath("$.monthly.length()").value(1))
				.andExpect(jsonPath("$.monthly[0].month").value("2026-09-01"))
				.andExpect(jsonPath("$.monthly[0].netPnl").value(-647.78))
				.andExpect(jsonPath("$.monthly[0].totalR").value(-64.778))
				.andExpect(jsonPath("$.daily.length()").value(22))
				.andExpect(jsonPath("$.daily[0].date").value("2026-09-01"));

		createAccount("Other performance user", EightcapFixture.LOGIN, "other@example.test");
		mockMvc.perform(get("/api/v1/trading-accounts/summary/performance")
						.param("accountIds", Long.toString(accountId))
						.with(user("other@example.test")))
				.andExpect(status().isNotFound());
	}

	@Test
	void derivesRiskFromLinkedStopAndFallsBackWhenStopMayHaveMoved() throws Exception {
		long stopAccount = createAccount(
				"Stop risk", EightcapFixture.LOGIN, USER, (short) 180, "FIXED_STOP_DISTANCE", new BigDecimal("5"));
		long movedAccount = createAccount(
				"Moved stop risk", EightcapFixture.LOGIN, USER, (short) 180, "FIXED_AMOUNT", new BigDecimal("5"));

		importStopRiskScenario(stopAccount, "701", "95");
		assertRisk(stopAccount, "701", "SL_ORDER", "10.00000", "2.00000000", null);
		assertRisk(stopAccount, "702", "ACCOUNT_RULE", "10.00000", "0.00000000", null);

		importStopRiskScenario(movedAccount, "703", "105");
		assertRisk(movedAccount, "703", "ACCOUNT_RULE", "5.00000", "4.00000000", "SL_MAY_HAVE_MOVED");
	}

	private void assertTradingViewImports() throws Exception {
		long overlapAccount = createAccount("TradingView overlap", EightcapFixture.LOGIN);
		List<List<String>> commonOrders = new ArrayList<>();
		for (int i = 0; i < 194; i++) {
			commonOrders.add(tradingViewOrder(
					"XAUUSD", "Buy", "Limit", "0.05", "", "", "", "", "Cancelled",
					"2026-10-10 22:00:00", "", "", "", "", Long.toString(30_000 + i)));
		}
		List<List<String>> firstOrders = new ArrayList<>(commonOrders);
		List<List<String>> secondOrders = new ArrayList<>(commonOrders);
		for (int i = 0; i < 5; i++) {
			firstOrders.add(tradingViewOrder(
					"XAUUSD", "Buy", "Limit", "0.05", "", "", "", "", "Cancelled",
					"2026-10-10 22:00:00", "", "", "", "", Long.toString(40_000 + i)));
			secondOrders.add(tradingViewOrder(
					"XAUUSD", "Buy", "Limit", "0.05", "", "", "", "", "Cancelled",
					"2026-10-10 22:00:00", "", "", "", "", Long.toString(50_000 + i)));
		}
		byte[] firstSnapshot = tradingViewCsv(firstOrders);
		byte[] secondSnapshot = tradingViewCsv(secondOrders);
		JsonNode firstPreview = previewTradingView(overlapAccount, "orders-1.csv", firstSnapshot);
		org.junit.jupiter.api.Assertions.assertEquals(199, firstPreview.path("totalRows").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(199, firstPreview.path("newOrders").asInt());
		importTradingView(overlapAccount, "orders-1.csv", firstSnapshot, firstPreview.path("fileSha256").asString());
		long batchesBeforeInvalidTvImports = importBatchCount();
		mockMvc.perform(multipart("/api/v1/trading-accounts/{id}/imports/tradingview", overlapAccount)
						.file(file("orders-1.csv", firstSnapshot))
						.param("sha256", "0".repeat(64))
						.with(user(USER)))
				.andExpect(status().isConflict());
		List<String> changedOrder = new ArrayList<>(firstOrders.get(0));
		changedOrder.set(3, "0.06");
		mockMvc.perform(multipart(
								"/api/v1/trading-accounts/{id}/imports/tradingview/preview", overlapAccount)
						.file(file("changed-order.csv", tradingViewCsv(List.of(changedOrder))))
						.with(user(USER)))
				.andExpect(status().isConflict());
		org.junit.jupiter.api.Assertions.assertEquals(batchesBeforeInvalidTvImports, importBatchCount());
		JsonNode secondPreview = previewTradingView(overlapAccount, "orders-2.csv", secondSnapshot);
		org.junit.jupiter.api.Assertions.assertEquals(199, secondPreview.path("totalRows").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(194, secondPreview.path("alreadyImportedOrders").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(5, secondPreview.path("newOrders").asInt());
		importTradingView(overlapAccount, "orders-2.csv", secondSnapshot, secondPreview.path("fileSha256").asString());
		org.junit.jupiter.api.Assertions.assertEquals(
				204,
				jdbcClient.sql("select count(*) from trade_orders where trading_account_id = ?")
						.param(overlapAccount).query(Integer.class).single());
		long tvOtherUser = createAccount("TradingView other user", EightcapFixture.LOGIN, "other@example.test");
		mockMvc.perform(multipart(
								"/api/v1/trading-accounts/{id}/imports/tradingview/preview", tvOtherUser)
						.file(file("orders-1.csv", firstSnapshot))
						.with(user(USER)))
				.andExpect(status().isNotFound());

		long tradingViewFirstAccount = createAccount("TradingView first", EightcapFixture.LOGIN);
		long reportFirstAccount = createAccount("Report first", EightcapFixture.LOGIN);
		List<List<String>> btcOrders = List.of(
				tradingViewOrder(
						"BTCUSD", "Buy", "Limit", "0.02", "0.02", "", "", "82900", "Filled",
						"2026-10-10 22:00:00", "BTCUSD:1250803562", "0", "", "", "1250803562"),
				tradingViewOrder(
						"BTCUSD", "Sell", "Market", "0.01", "0.01", "", "", "82958.5", "Filled",
						"2026-10-10 22:50:07", "BTCUSD:1250803562", "0", "-0.36", "-0.36", "1250803588"),
				tradingViewOrder(
						"BTCUSD", "Sell", "Market", "0.01", "0.01", "", "", "82958.5", "Filled",
						"2026-10-10 22:51:07", "BTCUSD:1250803562", "0", "-0.36", "-0.36", "1250803590"));
		byte[] btcReport = tradingViewCsv(btcOrders);
		byte[] eightcapBtc = EightcapFixture.csvBytes(List.of(List.of(
				"1250803562", EightcapFixture.LOGIN, "buy", "BTCUSD", "0.02", "0.00", "0.00",
				"2026-10-10 15:00:00", "82900.00", "2026-10-10 15:51:07", "82958.50",
				"-0.72", "USD", "BTC PARTIAL CLOSE")), false);

		JsonNode tvFirstPreview = previewTradingView(tradingViewFirstAccount, "btc-orders.csv", btcReport);
		org.junit.jupiter.api.Assertions.assertEquals(1, tvFirstPreview.path("newTrades").asInt());
		importTradingView(
				tradingViewFirstAccount, "btc-orders.csv", btcReport, tvFirstPreview.path("fileSha256").asString());
		assertSummary(List.of(tradingViewFirstAccount), 1, "-0.72", "0.00", "-0.72");
		org.junit.jupiter.api.Assertions.assertEquals(
				2,
				jdbcClient.sql("""
						select count(*) from trade_closes
						where trading_account_id = ? and source = 'TRADINGVIEW'
						""").param(tradingViewFirstAccount).query(Integer.class).single());
		JsonNode tvImportAgain = previewTradingView(tradingViewFirstAccount, "btc-orders.csv", btcReport);
		org.junit.jupiter.api.Assertions.assertEquals(3, tvImportAgain.path("alreadyImportedOrders").asInt());
		org.junit.jupiter.api.Assertions.assertEquals(0, tvImportAgain.path("newOrders").asInt());
		JsonNode reportPreview = preview(tradingViewFirstAccount, "btc-report.csv", eightcapBtc);
		importFile(
				tradingViewFirstAccount,
				"btc-report.csv",
				eightcapBtc,
				reportPreview.path("fileSha256").asString(),
				false,
				200);
		assertSummary(List.of(tradingViewFirstAccount), 1, "-0.72", "0.00", "-0.72");
		assertReportPrecedence(tradingViewFirstAccount);

		JsonNode reportFirstPreview = preview(reportFirstAccount, "btc-report.csv", eightcapBtc);
		importFile(
				reportFirstAccount,
				"btc-report.csv",
				eightcapBtc,
				reportFirstPreview.path("fileSha256").asString(),
				false,
				200);
		JsonNode tvAfterReportPreview = previewTradingView(reportFirstAccount, "btc-orders.csv", btcReport);
		importTradingView(
				reportFirstAccount, "btc-orders.csv", btcReport, tvAfterReportPreview.path("fileSha256").asString());
		assertSummary(List.of(reportFirstAccount), 1, "-0.72", "0.00", "-0.72");
		assertReportPrecedence(reportFirstAccount);
	}

	private void assertReportPrecedence(long accountId) {
		org.junit.jupiter.api.Assertions.assertEquals(
				1,
				jdbcClient.sql("""
						select count(*) from trade_closes
						where trading_account_id = ? and source = 'TRADES_REPORT'
						""").param(accountId).query(Integer.class).single());
		org.junit.jupiter.api.Assertions.assertEquals(
				0,
				jdbcClient.sql("""
						select count(*) from trade_closes
						where trading_account_id = ? and source = 'TRADINGVIEW'
						""").param(accountId).query(Integer.class).single());
		org.junit.jupiter.api.Assertions.assertEquals(
				1,
				jdbcClient.sql("""
						select count(*) from trades
						where trading_account_id = ? and has_trades_report and has_tradingview_data
						""").param(accountId).query(Integer.class).single());
		org.junit.jupiter.api.Assertions.assertEquals(
				3,
				jdbcClient.sql("select count(*) from trade_orders where trading_account_id = ? and trade_id is not null")
						.param(accountId).query(Integer.class).single());
	}

	private JsonNode previewTradingView(long accountId, String filename, byte[] content) throws Exception {
		MvcResult result = mockMvc.perform(multipart(
								"/api/v1/trading-accounts/{id}/imports/tradingview/preview", accountId)
						.file(file(filename, content))
						.with(user(USER)))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsByteArray());
	}

	private void importTradingView(long accountId, String filename, byte[] content, String hash) throws Exception {
		mockMvc.perform(multipart("/api/v1/trading-accounts/{id}/imports/tradingview", accountId)
						.file(file(filename, content))
						.param("sha256", hash)
						.with(user(USER)))
				.andExpect(status().isOk());
	}

	private void importStopRiskScenario(long accountId, String ticket, String stopPrice) throws Exception {
		List<List<String>> tradeRows = List.of(
				eightcapTradeRow(ticket, "20.00"),
				eightcapTradeRow(Long.toString(Long.parseLong(ticket) + 1), "0.00"));
		byte[] tradesReport = EightcapFixture.csvBytes(tradeRows, false);
		JsonNode preview = preview(accountId, "stop-risk.csv", tradesReport);
		importFile(accountId, "stop-risk.csv", tradesReport, preview.path("fileSha256").asString(), false, 200);

		String positionId = "XAUUSD:" + ticket;
		List<List<String>> orderRows = List.of(
				tradingViewOrder(
						"XAUUSD", "Buy", "Market", "0.20", "0.20", "", "", "100.00", "Filled",
						"2026-10-01 16:00:00", positionId, "0", "", "", ticket),
				tradingViewOrder(
						"XAUUSD", "Sell", "Stop", "0.20", "", "", stopPrice, "", "Cancelled",
						"2026-10-01 16:01:00", positionId, "0", "", "", ticket + "-sl"));
		byte[] orders = tradingViewCsv(orderRows);
		JsonNode tvPreview = previewTradingView(accountId, "stop-orders.csv", orders);
		importTradingView(accountId, "stop-orders.csv", orders, tvPreview.path("fileSha256").asString());
	}

	private void assertRisk(
			long accountId,
			String ticket,
			String source,
			String riskAmount,
			String rMultiple,
			String flag) {
		var risk = jdbcClient.sql("""
						select risk_source, risk_amount, r_multiple, r_flag
						from trades
						where trading_account_id = ? and ticket = ?
						""")
				.param(accountId)
				.param(ticket)
				.query((rs, rowNum) -> new Object[] {
						rs.getString("risk_source"),
						rs.getBigDecimal("risk_amount").toPlainString(),
						rs.getBigDecimal("r_multiple").toPlainString(),
						rs.getString("r_flag")
				})
				.single();
		org.junit.jupiter.api.Assertions.assertEquals(source, risk[0]);
		org.junit.jupiter.api.Assertions.assertEquals(riskAmount, risk[1]);
		org.junit.jupiter.api.Assertions.assertEquals(rMultiple, risk[2]);
		org.junit.jupiter.api.Assertions.assertEquals(flag, risk[3]);
		org.junit.jupiter.api.Assertions.assertEquals(
				rMultiple,
				jdbcClient.sql("""
						select c.r_multiple
						from trade_closes c join trades t on t.id = c.trade_id
						where t.trading_account_id = ? and t.ticket = ?
						""")
						.param(accountId).param(ticket).query(BigDecimal.class).single().toPlainString());
	}

	private List<String> eightcapTradeRow(String ticket, String profit) {
		return List.of(
				ticket,
				EightcapFixture.LOGIN,
				"buy",
				"XAUUSD",
				"0.20",
				"0.00",
				"0.00",
				"2026-10-01 09:00:00",
				"100.00",
				"2026-10-01 10:00:00",
				"110.00",
				profit,
				"USD",
				"STOP GTC COMPLETED");
	}

	private List<String> tradingViewOrder(
			String symbol,
			String side,
			String type,
			String quantity,
			String filledQuantity,
			String limit,
			String stop,
			String averageFill,
			String status,
			String updateTime,
			String positionId,
			String commission,
			String closedPnl,
			String netClosedPnl,
			String orderId) {
		return List.of(
				symbol, side, type, quantity, filledQuantity, limit, stop, averageFill, status,
				updateTime, positionId, commission, closedPnl, netClosedPnl, orderId);
	}

	private byte[] tradingViewCsv(List<List<String>> rows) {
		StringBuilder csv = new StringBuilder(String.join(",", List.of(
				"Symbol", "Side", "Type", "Qty", "Filled Qty", "Limit Price", "Stop Price",
				"Avg Fill Price", "Status", "Update Time", "Position ID", "Commission",
				"Closed P&L", "Net Closed P&L", "Order ID")));
		for (List<String> row : rows) {
			csv.append('\n').append(String.join(",", row));
		}
		return csv.toString().getBytes(StandardCharsets.UTF_8);
	}

	private long createAccount(String name, String login) throws Exception {
		return createAccount(name, login, USER);
	}

	private long createAccount(String name, String login, String owner) throws Exception {
		return createAccount(name, login, owner, null, null, null);
	}

	private long createAccount(
			String name,
			String login,
			String owner,
			Short offset,
			String defaultRiskMode,
			BigDecimal defaultRiskValue) throws Exception {
		byte[] body = objectMapper.writeValueAsBytes(
				new CreateAccountRequest(name, "Eightcap", "USD", login, offset, defaultRiskMode, defaultRiskValue));
		MvcResult result = mockMvc.perform(post("/api/v1/trading-accounts")
						.with(user(owner))
						.contentType("application/json")
						.content(body))
				.andExpect(status().isCreated())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("id").asLong();
	}

	private JsonNode preview(long accountId, String filename, byte[] content) throws Exception {
		MvcResult result = mockMvc.perform(multipart("/api/v1/trading-accounts/{id}/imports/eightcap/preview", accountId)
						.file(file(filename, content))
						.with(user(USER)))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsByteArray());
	}

	private JsonNode importFile(
			long accountId,
			String filename,
			byte[] content,
			String hash,
			boolean acknowledgeMismatch,
			int expectedStatus) throws Exception {
		MvcResult result = mockMvc.perform(multipart("/api/v1/trading-accounts/{id}/imports/eightcap", accountId)
						.file(file(filename, content))
						.param("sha256", hash)
						.param("acknowledgeLoginMismatch", Boolean.toString(acknowledgeMismatch))
						.with(user(USER)))
				.andExpect(status().is(expectedStatus))
				.andReturn();
		if (expectedStatus != 200) {
			return objectMapper.readTree(result.getResponse().getContentAsByteArray());
		}
		return objectMapper.readTree(result.getResponse().getContentAsByteArray());
	}

	private void assertSummary(
			List<Long> accountIds,
			int tradeCount,
			String grossPnl,
			String feesAndSwap,
			String netPnl) throws Exception {
		String ids = accountIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
		mockMvc.perform(get("/api/v1/trading-accounts/summary")
						.param("accountIds", ids)
						.with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tradeCount").value(tradeCount))
				.andExpect(jsonPath("$.grossPnl").value(Double.parseDouble(grossPnl)))
				.andExpect(jsonPath("$.feesAndSwap").value(Double.parseDouble(feesAndSwap)))
				.andExpect(jsonPath("$.netPnl").value(Double.parseDouble(netPnl)));
	}

	private void assertMalformedReportDoesNotWrite(long accountId) throws Exception {
		String malformed = String.join("\n",
				String.join(",", EightcapFixture.HEADERS),
				"T-VALID,synthetic-login-70508581,buy,XAUUSD,0.05,0,0,2026-09-01 09:00:01,4375.59,2026-09-01 14:41:50,4387.16,-2.00,USD,STOP",
				"T-INVALID,synthetic-login-70508581,buy,XAUUSD,0.05,0,0,2026-09-01 09:00:01,4375.59,2026-09-01 14:41:50,4387.16,not-a-number,USD,STOP");
		long batchesBefore = importBatchCount();
		mockMvc.perform(multipart("/api/v1/trading-accounts/{id}/imports/eightcap/preview", accountId)
						.file(new MockMultipartFile(
								"file", "malformed.csv", "text/csv", malformed.getBytes(StandardCharsets.UTF_8)))
						.with(user(USER)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("line 3, Profit")));
		org.junit.jupiter.api.Assertions.assertEquals(batchesBefore, importBatchCount());
		long tradesBefore = jdbcClient.sql("select count(*) from trades").query(Long.class).single();

		mockMvc.perform(multipart("/api/v1/trading-accounts/{id}/imports/eightcap/preview", accountId)
						.file(new MockMultipartFile(
								"file", "unsupported.txt", "text/plain", "not an Eightcap report".getBytes(StandardCharsets.UTF_8)))
						.with(user(USER)))
				.andExpect(status().isBadRequest());
		org.junit.jupiter.api.Assertions.assertEquals(batchesBefore, importBatchCount());
		org.junit.jupiter.api.Assertions.assertEquals(tradesBefore, jdbcClient.sql("select count(*) from trades").query(Long.class).single());
	}

	private long importBatchCount() {
		return jdbcClient.sql("select count(*) from import_batches").query(Long.class).single();
	}

	private MockMultipartFile file(String filename, byte[] content) {
		String contentType = filename.endsWith(".xlsx")
				? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
				: "text/csv";
		return new MockMultipartFile("file", filename, contentType, content);
	}

	private record CreateAccountRequest(
			String name,
			String broker,
			String currency,
			String brokerLogin,
			Short tradesReportUtcOffsetMinutes,
			String defaultRiskMode,
			BigDecimal defaultRiskValue) {
	}

	private record RiskRuleRequest(String defaultRiskMode, BigDecimal defaultRiskValue) {
	}

}
