package com.trading.journal.auth;

public class PasswordResetEmailException extends RuntimeException {

	public PasswordResetEmailException(String message, Throwable cause) {
		super(message, cause);
	}

	public PasswordResetEmailException(String message) {
		super(message);
	}

}
