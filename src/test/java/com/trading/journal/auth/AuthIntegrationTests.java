package com.trading.journal.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.concurrent.CopyOnWriteArrayList;
import jakarta.servlet.http.Cookie;
import com.trading.journal.JwtTestProperties;
import com.trading.journal.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AuthIntegrationTests.ResetEmailTestConfiguration.class})
class AuthIntegrationTests extends JwtTestProperties {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private ResetEmailStub resetEmailStub;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void clearResetEmailMessages() {
		resetEmailStub.clear();
	}

	@Test
	void signupRequiresTenCharacterPasswordAndAcceptsTenCharacters() throws Exception {
		mockMvc.perform(post("/api/v1/auth/sign-up")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsBytes(
								new Credentials("auth-nine-chars@example.test", "123456789"))))
				.andExpect(status().isBadRequest());

		signUp("auth-ten-chars@example.test", "1234567890");
	}

	@Test
	void duplicateSignupDoesNotRevealThatTheEmailExists() throws Exception {
		signUp("duplicate-signup@example.test", "long-enough-password");

		mockMvc.perform(post("/api/v1/auth/sign-up")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsBytes(
								new Credentials("duplicate-signup@example.test", "long-enough-password"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Unable to create account with these details."));
	}

	@Test
	void passwordResetUsesGenericRequestResponseAndAllowsOneSuccessfulReset() throws Exception {
		MvcResult signup = signUp("reset-one-time@example.test", "original-password");
		String oldRefreshToken = refreshToken(signup);
		String genericMessage = requestPasswordReset("reset-one-time@example.test");
		String resetUrl = resetEmailStub.messages().getFirst().resetUrl();
		String token = resetTokenFromUrl(resetUrl);

		org.junit.jupiter.api.Assertions.assertEquals(
				0L,
				jdbcClient.sql("select count(*) from password_reset_tokens where token_hash = ?")
						.param(token)
						.query(Long.class)
						.single());
		org.junit.jupiter.api.Assertions.assertEquals(
				1L,
				jdbcClient.sql("select count(*) from password_reset_tokens where token_hash = ?")
						.param(hashToken(token))
						.query(Long.class)
						.single());
		org.junit.jupiter.api.Assertions.assertEquals(
				genericMessage,
				requestPasswordReset("unknown-reset@example.test"));
		org.junit.jupiter.api.Assertions.assertEquals(1, resetEmailStub.messages().size());

		mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsBytes(
								new PasswordResetConfirmation(token, "replacement-password"))))
				.andExpect(status().isNoContent());

		mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsBytes(
								new PasswordResetConfirmation(token, "another-password"))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Reset link is invalid or expired."));

		signInFailure(new Credentials("reset-one-time@example.test", "original-password"));
		mockMvc.perform(post("/api/v1/auth/sign-in")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsBytes(
								new Credentials("reset-one-time@example.test", "replacement-password"))))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie("refresh_token", oldRefreshToken)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void expiredResetTokenIsRejectedAndResendFailureKeepsRequestGeneric() throws Exception {
		signUp("reset-expired@example.test", "original-password");
		requestPasswordReset("reset-expired@example.test");
		String expiredToken = resetTokenFromUrl(resetEmailStub.messages().getFirst().resetUrl());
		jdbcClient.sql("""
						update password_reset_tokens
						set created_at = current_timestamp - interval '2 seconds',
						    expires_at = current_timestamp - interval '1 second'
						where token_hash = ?
						""")
				.param(hashToken(expiredToken))
				.update();
		mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsBytes(
								new PasswordResetConfirmation(expiredToken, "replacement-password"))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Reset link is invalid or expired."));

		signUp("reset-delivery-failure@example.test", "original-password");
		resetEmailStub.failNext = true;
		String genericMessage = requestPasswordReset("reset-delivery-failure@example.test");
		ResetEmailStub.ResetEmail failureEmail = resetEmailStub.messages().getLast();
		String failedDeliveryToken = resetTokenFromUrl(failureEmail.resetUrl());
		org.junit.jupiter.api.Assertions.assertEquals(
				"If an account exists for this email, password-reset instructions will be sent.",
				genericMessage);
		org.junit.jupiter.api.Assertions.assertEquals(
				1L,
				jdbcClient.sql("""
								select count(*) from password_reset_tokens
								where token_hash = ? and invalidated_at is not null
								""")
						.param(hashToken(failedDeliveryToken))
						.query(Long.class)
						.single());
		mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsBytes(
								new PasswordResetConfirmation(failedDeliveryToken, "replacement-password"))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Reset link is invalid or expired."));
	}

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
		mockMvc.perform(post("/api/v1/trading-accounts")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Second account","broker":"Eightcap","currency":"USD"}
								"""))
				.andExpect(status().isCreated());
		mockMvc.perform(get("/api/v1/trading-accounts")
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
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

	private String requestPasswordReset(String email) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/auth/password-reset/request")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsBytes(new ResetRequest(email))))
				.andExpect(status().isAccepted())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsByteArray())
				.path("message")
				.asString();
	}

	private String resetTokenFromUrl(String resetUrl) {
		java.util.regex.Matcher matcher =
				java.util.regex.Pattern.compile("[?&]token=([A-Za-z0-9_-]+)").matcher(resetUrl);
		org.junit.jupiter.api.Assertions.assertTrue(matcher.find());
		return matcher.group(1);
	}

	private String hashToken(String token) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
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

	private record ResetRequest(String email) {
	}

	private record PasswordResetConfirmation(String token, String newPassword) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ResetEmailTestConfiguration {

		@Bean
		@Primary
		ResetEmailStub resetEmailStub() {
			return new ResetEmailStub();
		}
	}

	static class ResetEmailStub implements PasswordResetEmailSender {

		private final java.util.List<ResetEmail> sent = new CopyOnWriteArrayList<>();
		private volatile boolean failNext;

		@Override
		public void sendPasswordResetEmail(String recipient, String resetUrl) {
			sent.add(new ResetEmail(recipient, resetUrl));
			if (failNext) {
				failNext = false;
				throw new PasswordResetEmailException("Stubbed Resend failure.");
			}
		}

		java.util.List<ResetEmail> messages() {
			return new ArrayList<>(sent);
		}

		void clear() {
			sent.clear();
			failNext = false;
		}

		record ResetEmail(String recipient, String resetUrl) {
		}
	}

}
