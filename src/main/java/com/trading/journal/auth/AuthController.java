package com.trading.journal.auth;

import java.time.Duration;
import java.time.Instant;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private static final String REFRESH_COOKIE = "refresh_token";

	private final AuthService authService;
	private final boolean secureCookie;

	public AuthController(
			AuthService authService,
			@Value("${security.jwt.refresh-cookie-secure}") boolean secureCookie) {
		this.authService = authService;
		this.secureCookie = secureCookie;
	}

	@PostMapping("/sign-up")
	@ResponseStatus(HttpStatus.CREATED)
	public AuthResponse signUp(
			@Valid @RequestBody SignUpRequest request,
			HttpServletResponse response) {
		return respond(authService.signUp(request.email(), request.password()), response);
	}

	@PostMapping("/sign-in")
	public AuthResponse signIn(
			@Valid @RequestBody SignInRequest request,
			HttpServletResponse response) {
		return respond(authService.signIn(request.email(), request.password()), response);
	}

	@PostMapping("/refresh")
	public AuthResponse refresh(
			@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
			HttpServletResponse response) {
		return authService.rotateRefreshToken(refreshToken)
				.map(tokens -> respond(tokens, response))
				.orElseGet(() -> {
					clearRefreshCookie(response);
					throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token is invalid or expired.");
				});
	}

	@PostMapping("/sign-out")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void signOut(
			@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
			HttpServletResponse response) {
		authService.signOut(refreshToken);
		clearRefreshCookie(response);
	}

	@PostMapping("/password-reset/request")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public PasswordResetRequestResponse requestPasswordReset(
			@Valid @RequestBody PasswordResetRequest request) {
		return new PasswordResetRequestResponse(authService.requestPasswordReset(request.email()));
	}

	@PostMapping("/password-reset/confirm")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmation request) {
		authService.resetPassword(request.token(), request.newPassword());
	}

	private AuthResponse respond(AuthService.AuthTokens tokens, HttpServletResponse response) {
		Duration remaining = Duration.between(Instant.now(), tokens.refreshTokenExpiresAt());
		ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, tokens.refreshToken())
				.httpOnly(true)
				.secure(secureCookie)
				.sameSite("Strict")
				.path("/api/v1/auth")
				.maxAge(remaining.isNegative() ? Duration.ZERO : remaining)
				.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
		return new AuthResponse(tokens.accessToken(), "Bearer", tokens.accessTokenExpiresAt());
	}

	private void clearRefreshCookie(HttpServletResponse response) {
		ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, "")
				.httpOnly(true)
				.secure(secureCookie)
				.sameSite("Strict")
				.path("/api/v1/auth")
				.maxAge(Duration.ZERO)
				.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

	public record SignUpRequest(
			@NotBlank @Email @Size(max = 320) String email,
			@NotBlank @Size(min = 10, max = 72) String password) {
	}

	public record SignInRequest(
			@NotBlank @Email @Size(max = 320) String email,
			@NotBlank @Size(max = 72) String password) {
	}

	public record PasswordResetRequest(@NotBlank @Email @Size(max = 320) String email) {
	}

	public record PasswordResetConfirmation(
			@NotBlank @Size(max = 128) String token,
			@NotBlank @Size(min = 10, max = 72) String newPassword) {
	}

	public record PasswordResetRequestResponse(String message) {
	}

	public record AuthResponse(String accessToken, String tokenType, Instant expiresAt) {
	}

}
