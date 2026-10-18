package com.trading.journal.trade;

import java.math.BigDecimal;
import com.trading.journal.account.CurrentUserService;
import com.trading.journal.account.UserEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trades")
public class TradeController {

	private final CurrentUserService currentUserService;
	private final TradeManualRService manualR;

	public TradeController(CurrentUserService currentUserService, TradeManualRService manualR) {
		this.currentUserService = currentUserService;
		this.manualR = manualR;
	}

	@PutMapping("/{tradeId}/r")
	public TradeRResponse updateR(@PathVariable Long tradeId, @Valid @RequestBody ManualRRequest request) {
		UserEntity user = currentUserService.requireCurrentUser();
		TradeEntity trade = manualR.setManualR(tradeId, user.getId(), request.rMultiple());
		return new TradeRResponse(trade.getId(), trade.getRMultiple(), trade.getRSource(), trade.getRFlag());
	}

	public record ManualRRequest(@Digits(integer = 10, fraction = 8) BigDecimal rMultiple) {
	}

	public record TradeRResponse(Long id, BigDecimal rMultiple, String rSource, String rFlag) {
	}

}