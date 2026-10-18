package com.trading.journal.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import jakarta.servlet.http.Cookie;
import com.trading.journal.JwtTestProperties;
import com.trading.journal.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthIntegrationTests extends JwtTestProperties {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbcClient;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void signupReturnsBearerTokenAndProtectedApiRejectsMissingOrInvalidToken() throws Exception {
		MvcResult signup = signUp("auth-bearer@example.test", "long-enough-password");
		JsonNode response = objectMapper.readTree(signup.getResponse().getContentAsByteArray());
		String accessToken = response.path("accessToken").asString();
		String refreshToken = refreshToken(signup);

		org.junit.jupiter.api.Assertions.assertFalse(accessToken.isBlank());
		org.junit.jupiter.api.Assertions.assertEquals("Bearer", response.path("tokenType").asString());
		org.junit.jupiter.api.Assertions.assertTrue(signup.getResponse().getHeader("Set-Cookie").contains("HttpOnly"));
		org.junit.jupiter.api.Assertions.assertTrue(signup.getResponse().getHeader("Set-Cookie").contains("SameSite=Strict"));

		mockMvc.perform(get("/api/v1/trading-accounts")
						.header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isEmpty());

		mockMvc.perform(get("/api/v1/trading-accounts"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/trading-accounts")
						.header("Authorization", "Bearer " + accessToken + "tampered"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void refreshRotatesStoredTokenAndSignOutRevokesIt() throws Exception {
		MvcResult signup = signUp("auth-refresh@example.test", "long-enough-password");
		String originalRefresh = refreshToken(signup);
		org.junit.jupiter.api.Assertions.assertEquals(
				0L,
				jdbcClient.sql("select count(*) from refresh_tokens where token_hash = ?")
						.param(originalRefresh)
						.query(Long.class)
						.single());

		MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie("refresh_token", originalRefresh)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andReturn();
		String rotatedRefresh = refreshToken(refreshed);
		org.junit.jupiter.api.Assertions.assertNotEquals(originalRefresh, rotatedRefresh);

		mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie("refresh_token", originalRefresh)))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/api/v1/auth/sign-out")
						.cookie(new Cookie("refresh_token", rotatedRefresh)))
				.andExpect(status().isNoContent());
		mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie("refresh_token", rotatedRefresh)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void signInUsesGenericFailuresAndAllowsOnlyTheAccountOwner() throws Exception {
		signUp("auth-owner@example.test", "long-enough-password");
		MvcResult otherSignup = signUp("auth-other@example.test", "another-long-password");

		MvcResult signin = mockMvc.perform(post("/api/v1/auth/sign-in")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsBytes(
								new Credentials("AUTH-OWNER@example.test", "long-enough-password"))))
				.andExpect(status().isOk())
				.andReturn();
		String ownerToken = objectMapper.readTree(signin.getResponse().getContentAsByteArray())
				.path("accessToken")
				.asString();
		MvcResult account = mockMvc.perform(post("/api/v1/trading-accounts")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Private","broker":"Eightcap","currency":"USD"}
								"""))
				.andExpect(status().isCreated())
				.andReturn();
		long accountId = objectMapper.readTree(account.getResponse().getContentAsByteArray())
				.path("id")
				.asLong();
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
						"/api/v1/trading-accounts/{id}", accountId)
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Private renamed","broker":"Eightcap EU","currency":"EUR"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Private renamed"))
				.andExpect(jsonPath("$.broker").value("Eightcap EU"))
				.andExpect(jsonPath("$.currency").value("EUR"));
		mockMvc.perform(get("/api/v1/trading-accounts")
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("Private renamed"));
		String otherToken = objectMapper.readTree(otherSignup.getResponse().getContentAsByteArray())
				.path("accessToken")
				.asString();
		mockMvc.perform(get("/api/v1/trading-accounts/summary")
						.param("accountIds", Long.toString(accountId))
						.header("Authorization", "Bearer " + otherToken))
				.andExpect(status().isNotFound());

		String wrongPasswordDetail = signInFailure(new Credentials(
				"auth-owner@example.test", "wrong-password"));
		String unknownEmailDetail = signInFailure(new Credentials(
				"unknown-auth@example.test", "wrong-password"));
		org.junit.jupiter.api.Assertions.assertEquals(wrongPasswordDetail, unknownEmailDetail);
		org.junit.jupiter.api.Assertions.assertEquals("Email or password is incorrect.", wrongPasswordDetail);

		signUp("auth-rate-limit@example.test", "another-long-password");
		for (int attempt = 0; attempt < 6; attempt++) {
			org.junit.jupiter.api.Assertions.assertEquals(
					"Email or password is incorrect.",
					signInFailure(new Credentials("auth-rate-limit@example.test", "incorrect-password")));
		}
		mockMvc.perform(post("/api/v1/auth/sign-in")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsBytes(
								new Credentials("auth-rate-limit@example.test", "another-long-password"))))
				.andExpect(status().isUnauthorized());
	}

	private MvcResult signUp(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/sign-up")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsBytes(new Credentials(email, password))))
				.andExpect(status().isCreated())
				.andReturn();
	}

	private String signInFailure(Credentials credentials) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/auth/sign-in")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsBytes(credentials)))
				.andExpect(status().isUnauthorized())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("detail").asString();
	}

	private String refreshToken(MvcResult result) {
		String setCookie = result.getResponse().getHeader("Set-Cookie");
		org.junit.jupiter.api.Assertions.assertNotNull(setCookie);
		java.util.regex.Matcher matcher =
				java.util.regex.Pattern.compile("refresh_token=([^;]+)").matcher(setCookie);
		org.junit.jupiter.api.Assertions.assertTrue(matcher.find());
		return matcher.group(1);
	}

	private record Credentials(String email, String password) {
	}

}
