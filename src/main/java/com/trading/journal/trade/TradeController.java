package com.trading.journal.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import com.trading.journal.account.CurrentUserService;
import com.trading.journal.account.UserEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trades")
public class TradeController {

	private final CurrentUserService currentUserService;
	private final TradeManualRService manualR;
	private final TradeRepository trades;
	private final TradeCloseRepository tradeCloses;

	public TradeController(
			CurrentUserService currentUserService,
			TradeManualRService manualR,
			TradeRepository trades,
			TradeCloseRepository tradeCloses) {
		this.currentUserService = currentUserService;
		this.manualR = manualR;
		this.trades = trades;
		this.tradeCloses = tradeCloses;
	}

	@GetMapping("/{tradeId}")
	public TradeDetailResponse detail(@PathVariable Long tradeId) {
		UserEntity user = currentUserService.requireCurrentUser();
		TradeEntity trade = trades.findByIdAndUserIdAndDeletedAtIsNull(tradeId, user.getId())
				.orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
						org.springframework.http.HttpStatus.NOT_FOUND, "Trade was not found."));
		List<TradeCloseResponse> closes = tradeCloses
				.findAllByTradingAccountIdAndTradeId(trade.getTradingAccountId(), trade.getId())
				.stream()
				.sorted(Comparator.comparing(TradeCloseEntity::getClosedAt, Comparator.nullsLast(Comparator.naturalOrder())))
				.map(TradeCloseResponse::from)
				.toList();
		return TradeDetailResponse.from(trade, closes);
	}

	@DeleteMapping("/{tradeId}")
	@ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long tradeId) {
		UserEntity user = currentUserService.requireCurrentUser();
		TradeEntity trade = trades.findByIdAndUserIdAndDeletedAtIsNull(tradeId, user.getId())
				.orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
						org.springframework.http.HttpStatus.NOT_FOUND, "Trade was not found."));
		trade.softDelete(Instant.now());
		trades.save(trade);
	}

	@PutMapping("/{tradeId}/restore")
	@ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
	public void restore(@PathVariable Long tradeId) {
		UserEntity user = currentUserService.requireCurrentUser();
		TradeEntity trade = trades.findByIdAndUserId(tradeId, user.getId())
				.filter(item -> item.getDeletedAt() != null)
				.orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
						org.springframework.http.HttpStatus.NOT_FOUND, "Deleted trade was not found."));
		trade.restore();
		trades.save(trade);
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

	public record TradeDetailResponse(
			Long id,
			Long tradingAccountId,
			String ticket,
			String symbol,
			String side,
			String status,
			Instant openedAt,
			LocalDateTime openTimeServer,
			BigDecimal openPrice,
			BigDecimal openedVolume,
			BigDecimal grossPnl,
			BigDecimal feesAndSwap,
			BigDecimal netPnl,
			BigDecimal rMultiple,
			String rSource,
			String rFlag,
			boolean hasTradesReport,
			boolean hasTradingViewData,
			List<TradeCloseResponse> closes) {

		private static TradeDetailResponse from(TradeEntity trade, List<TradeCloseResponse> closes) {
			return new TradeDetailResponse(
					trade.getId(),
					trade.getTradingAccountId(),
					trade.getTicket(),
					trade.getSymbol(),
					trade.getSide(),
					trade.getStatus(),
					trade.getOpenedAt(),
					trade.getOpenTimeServer(),
					trade.getOpenPrice(),
					trade.getOpenedVolume(),
					trade.getGrossPnl(),
					trade.getFeesAndSwap(),
					trade.getNetPnl(),
					trade.getRMultiple(),
					trade.getRSource(),
					trade.getRFlag(),
					trade.isHasTradesReport(),
					trade.isHasTradingViewData(),
					closes);
		}
	}

	public record TradeCloseResponse(
			Long id,
			String source,
			BigDecimal volume,
			LocalDateTime closeTimeServer,
			Instant closedAt,
			BigDecimal closePrice,
			BigDecimal grossPnl,
			BigDecimal swaps,
			BigDecimal commission,
			BigDecimal netPnl,
			BigDecimal rMultiple) {

		private static TradeCloseResponse from(TradeCloseEntity close) {
			return new TradeCloseResponse(
					close.getId(),
					close.getSource(),
					close.getVolume(),
					close.getCloseTimeServer(),
					close.getClosedAt(),
					close.getClosePrice(),
					close.getGrossPnl(),
					close.getSwaps(),
					close.getCommission(),
					close.getNetPnl(),
					close.getRMultiple());
		}
	}

}