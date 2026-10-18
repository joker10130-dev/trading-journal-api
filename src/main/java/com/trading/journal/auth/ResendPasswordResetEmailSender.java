package com.trading.journal.auth;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class ResendPasswordResetEmailSender implements PasswordResetEmailSender {

	private final RestClient resendClient;
	private final String apiKey;
	private final String senderEmail;

	public ResendPasswordResetEmailSender(
			@Value("${security.password-reset.resend-api-key}") String apiKey,
			@Value("${security.password-reset.sender-email}") String senderEmail) {
		this.resendClient = RestClient.builder().baseUrl("https://api.resend.com").build();
		this.apiKey = apiKey;
		this.senderEmail = senderEmail;
	}

	@Override
	public void sendPasswordResetEmail(String recipient, String resetUrl) {
		if (apiKey.isBlank() || senderEmail.isBlank()) {
			throw new PasswordResetEmailException(
					"Resend API key and verified sender email must be configured.");
		}
		Map<String, Object> request = Map.of(
				"from", senderEmail,
				"to", List.of(recipient),
				"subject", "Reset your Trading Journal password",
				"text", "Use this one-time link to reset your password. It expires in 30 minutes: " + resetUrl);
		try {
			resendClient.post()
					.uri("/emails")
					.header("Authorization", "Bearer " + apiKey)
					.contentType(MediaType.APPLICATION_JSON)
					.body(request)
					.retrieve()
					.toBodilessEntity();
		} catch (RestClientException exception) {
			throw new PasswordResetEmailException("Resend could not deliver the password-reset email.", exception);
		}
	}

}
