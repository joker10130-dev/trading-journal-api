package com.trading.journal.trade;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.trading.journal.account.CurrentUserService;
import com.trading.journal.account.UserEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trading-accounts/{accountId}/trades")
public class ManualTradeController {

	private final CurrentUserService currentUserService;
	private final ManualTradeService manualTradeService;

	public ManualTradeController(CurrentUserService currentUserService, ManualTradeService manualTradeService) {
		this.currentUserService = currentUserService;
		this.manualTradeService = manualTradeService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ManualTradeService.ManualTradeResult create(
			@PathVariable Long accountId,
			@Valid @RequestBody CreateManualTradeRequest request) {
		UserEntity user = currentUserService.requireCurrentUser();
		return manualTradeService.create(accountId, user, new ManualTradeService.ManualTradeInput(
				request.ticket(),
				request.symbol(),
				request.side(),
				request.volume(),
				request.openedAt(),
				request.closedAt(),
				request.openPrice(),
				request.closePrice(),
				request.profit(),
				request.swaps(),
				request.commission(),
				request.rMultiple()));
	}

	public record CreateManualTradeRequest(
			@Size(max = 64) String ticket,
			@NotBlank @Size(max = 32) String symbol,
			@NotBlank @Pattern(regexp = "LONG|SHORT", flags = Pattern.Flag.CASE_INSENSITIVE) String side,
			@NotNull @DecimalMin("0.00001") @Digits(integer = 13, fraction = 5) BigDecimal volume,
			@NotNull LocalDateTime openedAt,
			@NotNull LocalDateTime closedAt,
			@NotNull @DecimalMin("0.00001") @Digits(integer = 13, fraction = 5) BigDecimal openPrice,
			@NotNull @DecimalMin("0.00001") @Digits(integer = 13, fraction = 5) BigDecimal closePrice,
			@NotNull @Digits(integer = 13, fraction = 5) BigDecimal profit,
			@NotNull @Digits(integer = 13, fraction = 5) BigDecimal swaps,
			@NotNull @Digits(integer = 13, fraction = 5) BigDecimal commission,
			@Digits(integer = 10, fraction = 8) BigDecimal rMultiple) {
	}

}
