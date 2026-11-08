package com.trading.journal.context;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import com.trading.journal.JwtTestProperties;
import com.trading.journal.TestcontainersConfiguration;
import com.trading.journal.account.TradingAccountEntity;
import com.trading.journal.account.TradingAccountRepository;
import com.trading.journal.account.UserEntity;
import com.trading.journal.account.UserRepository;
import com.trading.journal.trade.TradeCloseEntity;
import com.trading.journal.trade.TradeCloseRepository;
import com.trading.journal.trade.TradeEntity;
import com.trading.journal.trade.TradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@SpringBootTest(properties = "journal.image-storage.path=target/trade-context-test-images")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TradeContextIntegrationTests extends JwtTestProperties {

	private static final String USER = "trade-context@example.test";
	private static final String OTHER_USER = "trade-context-other@example.test";
	private static final ZoneId USER_ZONE = ZoneId.of("Asia/Bangkok");
	private static final AtomicLong SEQUENCE = new AtomicLong();
	private static final byte[] PNG_HEADER = {
			(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
	};

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
	private TagRepository tags;

	@Autowired
	private TradeTagMappingRepository mappings;

	@Autowired
	private TradeImageRepository images;

	@Autowired
	private TradeContextService context;

	private UserEntity owner;
	private UserEntity otherUser;
	private TradingAccountEntity account;

	@BeforeEach
	void setUp() throws IOException {
		Path imageDirectory = Path.of("target/trade-context-test-images");
		Files.createDirectories(imageDirectory);
		try (var files = Files.list(imageDirectory)) {
			for (Path file : files.toList()) Files.delete(file);
		}
		owner = users.findByEmail(USER).orElseGet(() -> users.saveAndFlush(new UserEntity(USER)));
		otherUser = users.findByEmail(OTHER_USER).orElseGet(() -> users.saveAndFlush(new UserEntity(OTHER_USER)));
		account = accounts.saveAndFlush(new TradingAccountEntity(
				owner.getId(),
				"Context " + SEQUENCE.incrementAndGet(),
				"Eightcap",
				"USD",
				null,
				(short) 180));
	}

	@Test
	void tagsAreUniquePerUserAndContextIsTenantScopedAndSurvivesRestore() throws Exception {
		long setupTagId = createTag("Breakout", "SETUP", null, USER);
		createTag("breakout", "OTHER", null, OTHER_USER);
		long temporaryTagId = createTag("Temporary", "OTHER", null, USER);
		mockMvc.perform(put("/api/v1/tags/{tagId}", setupTagId)
						.with(user(USER))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Breakout setup","category":"SETUP"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Breakout setup"));
		mockMvc.perform(delete("/api/v1/tags/{tagId}", temporaryTagId).with(user(USER)))
				.andExpect(status().isNoContent());
		mockMvc.perform(post("/api/v1/tags")
						.with(user(USER))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":" breakout setup ","category":"SETUP"}
								"""))
				.andExpect(status().isConflict());
		mockMvc.perform(post("/api/v1/tags")
						.with(user(USER))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"No sentiment","category":"EMOTION"}
								"""))
				.andExpect(status().isBadRequest());

		TradeEntity trade = createTrade(account, "XAUUSD");
		mockMvc.perform(put("/api/v1/trades/{tradeId}/tags", trade.getId())
						.with(user(USER))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"tagIds\":[" + setupTagId + "]}"))
				.andExpect(status().isNoContent());
		mockMvc.perform(put("/api/v1/trades/{tradeId}/note", trade.getId())
						.with(user(USER))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"text\":\"Waited for a setup.\"}"))
				.andExpect(status().isNoContent());
		mockMvc.perform(put("/api/v1/trades/{tradeId}/note", trade.getId())
						.with(user(USER))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"text\":\"Waited for confirmation.\"}"))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/trades/{tradeId}/context", trade.getId()).with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tags[0].name").value("Breakout setup"))
				.andExpect(jsonPath("$.note").value("Waited for confirmation."));
		trade.rebuildImportedSummary(
				trade.getSymbol(),
				trade.getSide(),
				trade.getStatus(),
				null,
				trade.getOpenedAt(),
				trade.getOpenPrice(),
				trade.getOpenedVolume(),
				trade.getGrossPnl(),
				trade.getFeesAndSwap(),
				trade.getNetPnl(),
				true,
				true);
		trades.saveAndFlush(trade);
		mockMvc.perform(get("/api/v1/trades/{tradeId}/context", trade.getId()).with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tags[0].name").value("Breakout setup"))
				.andExpect(jsonPath("$.note").value("Waited for confirmation."));
		mockMvc.perform(get("/api/v1/trades/{tradeId}/context", trade.getId()).with(user(OTHER_USER)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/trading-accounts/{accountId}/trades", account.getId())
						.param("tagIds", Long.toString(setupTagId))
						.with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].tags[0]").value("Breakout setup"));
		mockMvc.perform(get("/api/v1/trading-accounts/{accountId}/trades", account.getId())
						.param("tagIds", Long.toString(tags.findAllByUserIdOrderByName(otherUser.getId()).getFirst().getId()))
						.with(user(USER)))
				.andExpect(status().isNotFound());

		mockMvc.perform(delete("/api/v1/trades/{tradeId}", trade.getId()).with(user(USER)))
				.andExpect(status().isNoContent());
		mockMvc.perform(put("/api/v1/trades/{tradeId}/restore", trade.getId()).with(user(USER)))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/trades/{tradeId}/context", trade.getId()).with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tags[0].name").value("Breakout setup"))
				.andExpect(jsonPath("$.note").value("Waited for confirmation."));
		mockMvc.perform(delete("/api/v1/trades/{tradeId}/note", trade.getId()).with(user(USER)))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/trades/{tradeId}/context", trade.getId()).with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.note").value(""));
	}

	@Test
	void emotionMeterCountsDistinctTradesAndHonorsAccountAndSymbolFilters() throws Exception {
		TagEntity positive = tags.saveAndFlush(new TagEntity(owner.getId(), "Calm", "EMOTION", "POSITIVE"));
		TagEntity negative = tags.saveAndFlush(new TagEntity(owner.getId(), "Anxious", "EMOTION", "NEGATIVE"));
		TradeEntity positiveAndNegative = createClosedTrade(account, "XAUUSD");
		TradeEntity negativeOnly = createClosedTrade(account, "XAUUSD");
		TradeEntity wrongSymbol = createClosedTrade(account, "BTCUSD");
		mappings.saveAll(List.of(
				mapping(positiveAndNegative, positive),
				mapping(positiveAndNegative, negative),
				mapping(negativeOnly, negative),
				mapping(wrongSymbol, positive)));

		mockMvc.perform(get("/api/v1/trading-accounts/summary/dashboard")
						.param("period", "THIS_MONTH")
						.param("accountIds", account.getId().toString())
						.param("symbols", "XAUUSD")
						.with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.emotionMeter.positiveTradeCount").value(1))
				.andExpect(jsonPath("$.emotionMeter.emotionTradeCount").value(2))
				.andExpect(jsonPath("$.emotionMeter.positivePercentage").value(50));
	}

	@Test
	void imagesRequireValidContentAndRemainPrivateAndArePurgedOnHardDelete() throws Exception {
		TradeEntity trade = createTrade(account, "XAUUSD");
		MockMultipartFile validPng = new MockMultipartFile("file", "chart.txt", "text/plain", PNG_HEADER);
		var response = mockMvc.perform(multipart("/api/v1/trades/{tradeId}/images", trade.getId())
						.file(validPng)
						.with(user(USER)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.contentType").value("image/png"))
				.andReturn();
		String payload = response.getResponse().getContentAsString();
		long imageId = new tools.jackson.databind.ObjectMapper().readTree(payload).path("id").asLong();
		var secondResponse = mockMvc.perform(multipart("/api/v1/trades/{tradeId}/images", trade.getId())
						.file(validPng)
						.with(user(USER)))
				.andExpect(status().isCreated())
				.andReturn();
		long retainedImageId = new tools.jackson.databind.ObjectMapper()
				.readTree(secondResponse.getResponse().getContentAsString())
				.path("id")
				.asLong();
		mockMvc.perform(get("/api/v1/trades/{tradeId}/images/{imageId}", trade.getId(), imageId)
						.with(user(OTHER_USER)))
				.andExpect(status().isNotFound());

		mockMvc.perform(multipart("/api/v1/trades/{tradeId}/images", trade.getId())
						.file(new MockMultipartFile("file", "spoof.png", "image/png", "not an image".getBytes()))
						.with(user(USER)))
				.andExpect(status().isBadRequest());
		mockMvc.perform(multipart("/api/v1/trades/{tradeId}/images", trade.getId())
						.file(new MockMultipartFile("file", "large.png", "image/png", oversizedPng()))
						.with(user(USER)))
				.andExpect(status().isBadRequest());
		mockMvc.perform(put("/api/v1/trades/{tradeId}/note", trade.getId())
						.with(user(USER))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"text\":\"Keep on restore.\"}"))
				.andExpect(status().isNoContent());
		mockMvc.perform(delete("/api/v1/trades/{tradeId}", trade.getId()).with(user(USER)))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/trades/{tradeId}/images/{imageId}", trade.getId(), retainedImageId).with(user(USER)))
				.andExpect(status().isNotFound());
		mockMvc.perform(put("/api/v1/trades/{tradeId}/restore", trade.getId()).with(user(USER)))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/trades/{tradeId}/images/{imageId}", trade.getId(), retainedImageId)
						.with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
						.contentType("image/png"));
		mockMvc.perform(delete("/api/v1/trades/{tradeId}/images/{imageId}", trade.getId(), imageId)
						.with(user(USER)))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/trades/{tradeId}/images/{imageId}", trade.getId(), imageId)
						.with(user(USER)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/trades/{tradeId}/context", trade.getId()).with(user(USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.note").value("Keep on restore."));
		context.purgeForHardDelete(trade.getId(), owner.getId());
		trades.deleteById(trade.getId());
		org.junit.jupiter.api.Assertions.assertTrue(images.findAllByTradeIdAndUserIdOrderByCreatedAt(
				trade.getId(), owner.getId()).isEmpty());
		Path imageDirectory = Path.of("target/trade-context-test-images");
		try (var files = Files.list(imageDirectory)) {
			org.junit.jupiter.api.Assertions.assertEquals(0, files.count());
		}
	}

	private long createTag(String name, String category, String sentiment, String username) throws Exception {
		String json = sentiment == null
				? "{\"name\":\"" + name + "\",\"category\":\"" + category + "\"}"
				: "{\"name\":\"" + name + "\",\"category\":\"" + category + "\",\"sentiment\":\"" + sentiment + "\"}";
		var result = mockMvc.perform(post("/api/v1/tags")
						.with(user(username))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isCreated())
				.andReturn();
		return new tools.jackson.databind.ObjectMapper()
				.readTree(result.getResponse().getContentAsString())
				.path("id")
				.asLong();
	}

	private TradeEntity createTrade(TradingAccountEntity tradingAccount, String symbol) {
		return trades.saveAndFlush(new TradeEntity(
				tradingAccount.getUserId(),
				tradingAccount.getId(),
				null,
				symbol,
				"LONG",
				Instant.now(),
				new BigDecimal("100"),
				BigDecimal.ONE,
				BigDecimal.ZERO,
				BigDecimal.ZERO,
				BigDecimal.ZERO,
				null));
	}

	private TradeEntity createClosedTrade(TradingAccountEntity tradingAccount, String symbol) {
		Instant closedAt = Instant.now();
		TradeEntity trade = createTrade(tradingAccount, symbol);
		closes.saveAndFlush(new TradeCloseEntity(
				tradingAccount.getUserId(),
				tradingAccount.getId(),
				trade.getId(),
				null,
				"%064d".formatted(SEQUENCE.incrementAndGet()),
				BigDecimal.ONE,
				LocalDateTime.ofInstant(closedAt, USER_ZONE),
				closedAt,
				new BigDecimal("101"),
				BigDecimal.ZERO,
				BigDecimal.ZERO,
				BigDecimal.ZERO,
				BigDecimal.ZERO));
		return trade;
	}

	private TradeTagMappingEntity mapping(TradeEntity trade, TagEntity tag) {
		return new TradeTagMappingEntity(trade.getId(), tag.getId(), account.getId(), owner.getId());
	}

	private byte[] oversizedPng() {
		byte[] content = new byte[5 * 1024 * 1024 + 1];
		System.arraycopy(PNG_HEADER, 0, content, 0, PNG_HEADER.length);
		return content;
	}
}
