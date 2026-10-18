UPDATE trade_closes close_row
SET r_multiple = NULL
FROM trades trade
WHERE close_row.trade_id = trade.id
  AND trade.r_source <> 'MANUAL';

UPDATE trades
SET r_multiple = NULL,
    r_source = 'NONE'
WHERE r_source <> 'MANUAL';

UPDATE trades
SET r_flag = CASE
    WHEN r_multiple IS NOT NULL AND ABS(r_multiple) > 5 THEN 'R_ABSOLUTE_OVER_5'
    ELSE NULL
END;

ALTER TABLE trades
    DROP CONSTRAINT trades_risk_source_check,
    DROP CONSTRAINT trades_r_source_check,
    DROP COLUMN initial_sl_price,
    DROP COLUMN risk_amount,
    DROP COLUMN risk_source,
    ADD CONSTRAINT trades_r_source_check CHECK (r_source IN ('MANUAL', 'NONE'));

ALTER TABLE trading_accounts
    DROP CONSTRAINT trading_accounts_risk_mode_check,
    DROP CONSTRAINT trading_accounts_risk_value_check,
    DROP COLUMN default_risk_mode,
    DROP COLUMN default_risk_value;
