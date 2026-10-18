package com.trading.journal.auth;

public interface PasswordResetEmailSender {

	void sendPasswordResetEmail(String recipient, String resetUrl);

}
