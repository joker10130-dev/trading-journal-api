package com.trading.journal.account;

import java.math.BigDecimal;
import com.trading.journal.trade.TradeRiskCalculator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TradingAccountRiskService {

	private final TradingAccountRepository accounts;
	private final TradeRiskCalculator tradeRiskCalculator;

	public TradingAccountRiskService(
			TradingAccountRepository accounts,
			TradeRiskCalculator tradeRiskCalculator) {
		this.accounts = accounts;
		this.tradeRiskCalculator = tradeRiskCalculator;
	}

	@Transactional
	public TradingAccountEntity updateRule(
			Long accountId,
			Long userId,
			String riskMode,
			BigDecimal riskValue) {
		if ((riskMode == null) != (riskValue == null)) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST, "Default risk mode and value must be provided together.");
		}
		TradingAccountEntity account = accounts.findByIdAndUserIdForUpdate(accountId, userId)
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "Trading account was not found."));
		account.updateDefaultRiskRule(riskMode, riskValue);
		accounts.saveAndFlush(account);
		tradeRiskCalculator.recalculate(account);
		return account;
	}

}
