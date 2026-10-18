package com.trading.journal.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.trading.journal.account.TradingAccountRepository;
import com.trading.journal.account.UserEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ManualTradeService {

	private static final int R_SCALE = 8;

	private final TradingAccountRepository accounts;
	private final TradeRepository trades;
	private final TradeCloseRepository tradeCloses;

	public ManualTradeService(
			TradingAccountRepository accounts,
			TradeRepository trades,
			TradeCloseRepository tradeCloses) {
		this.accounts = accounts;
		this.trades = trades;
		this.tradeCloses = tradeCloses;
	}

	@Transactional
	public ManualTradeResult create(Long accountId, UserEntity user, ManualTradeInput input) {
		accounts.findByIdAndUserIdForUpdate(accountId, user.getId())
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "Trading account was not found."));

		String ticket = normalizeTicket(input.ticket());
		String symbol = input.symbol().trim().toUpperCase(Locale.ROOT);
		String side = input.side().toUpperCase(Locale.ROOT);
		if (ticket != null && trades.findByTradingAccountIdAndTicket(accountId, ticket).isPresent()) {
			throw new ResponseStatusException(
					HttpStatus.CONFLICT, "A trade with this ticket already exists in this trading account.");
		}

		validateTimes(input.openedAt(), input.closedAt());
		BigDecimal feesAndSwap = normalizeMoney(input.swaps().add(input.commission()), "Fees and swap");
		BigDecimal netPnl = normalizeMoney(input.profit().add(feesAndSwap), "Net P&L");
		BigDecimal rMultiple = input.rMultiple() == null
				? null
				: input.rMultiple().setScale(R_SCALE, RoundingMode.HALF_UP);
		ZoneId userZone = ZoneId.of(user.getTimezone());
		Instant openedAt = input.openedAt().atZone(userZone).toInstant();
		Instant closedAt = input.closedAt().atZone(userZone).toInstant();

		TradeEntity trade = trades.saveAndFlush(new TradeEntity(
				user.getId(),
				accountId,
				ticket,
				symbol,
				side,
				openedAt,
				input.openPrice(),
				input.volume(),
				input.profit(),
				feesAndSwap,
				netPnl,
				rMultiple));

		tradeCloses.save(new TradeCloseEntity(
				user.getId(),
				accountId,
				trade.getId(),
				UUID.randomUUID().toString().replace("-", ""),
				input.volume(),
				closedAt,
				input.closePrice(),
				input.profit(),
				input.swaps(),
				input.commission(),
				netPnl,
				rMultiple));

		return new ManualTradeResult(
				trade.getId(),
				ticket,
				symbol,
				side,
				input.volume(),
				openedAt,
				closedAt,
				input.openPrice(),
				input.closePrice(),
				input.profit(),
				input.swaps(),
				input.commission(),
				netPnl,
				rMultiple,
				trade.getRFlag(),
				warnings(input, side));
	}

	private String normalizeTicket(String ticket) {
		return ticket == null || ticket.isBlank() ? null : ticket.trim();
	}

	private BigDecimal normalizeMoney(BigDecimal amount, String field) {
		BigDecimal normalized = amount.setScale(5, RoundingMode.HALF_UP);
		if (normalized.precision() > 18) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST, field + " exceeds the supported precision.");
		}
		return normalized;
	}

	private void validateTimes(LocalDateTime openedAt, LocalDateTime closedAt) {
		if (closedAt.isBefore(openedAt)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Close time must not be before open time.");
		}
	}

	private List<String> warnings(ManualTradeInput input, String side) {
		List<String> warnings = new ArrayList<>();
		int priceDirection = input.closePrice().compareTo(input.openPrice());
		boolean favorableMove = "LONG".equals(side) ? priceDirection > 0 : priceDirection < 0;
		if (priceDirection != 0
				&& ((favorableMove && input.profit().signum() < 0)
				|| (!favorableMove && input.profit().signum() > 0))) {
			warnings.add("PROFIT_DIRECTION_MISMATCH");
		}
		return List.copyOf(warnings);
	}

	public record ManualTradeInput(
			String ticket,
			String symbol,
			String side,
			BigDecimal volume,
			LocalDateTime openedAt,
			LocalDateTime closedAt,
			BigDecimal openPrice,
			BigDecimal closePrice,
			BigDecimal profit,
			BigDecimal swaps,
			BigDecimal commission,
			BigDecimal rMultiple) {
	}

	public record ManualTradeResult(
			Long id,
			String ticket,
			String symbol,
			String side,
			BigDecimal volume,
			Instant openedAt,
			Instant closedAt,
			BigDecimal openPrice,
			BigDecimal closePrice,
			BigDecimal profit,
			BigDecimal swaps,
			BigDecimal commission,
			BigDecimal netPnl,
			BigDecimal rMultiple,
			String rFlag,
			List<String> warnings) {
	}

}
