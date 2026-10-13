-- r_multiple and r_flag already exist on trades (V1); this adds the R source.
ALTER TABLE trades
    ADD COLUMN r_source VARCHAR(24) NOT NULL DEFAULT 'NONE',
    ADD CONSTRAINT trades_r_source_check CHECK (r_source IN ('AUTO', 'MANUAL', 'NONE'));

-- Clear R values derived by the removed inference and account-rule logic.
UPDATE trade_closes
SET r_multiple = NULL
WHERE trade_id IN (SELECT id FROM trades WHERE risk_source IN ('ACCOUNT_RULE', 'SL_ORDER'));

UPDATE trades
SET initial_sl_price = NULL,
    risk_amount = NULL,
    risk_source = 'NONE',
    r_multiple = NULL,
    r_flag = NULL
WHERE risk_source IN ('ACCOUNT_RULE', 'SL_ORDER');
