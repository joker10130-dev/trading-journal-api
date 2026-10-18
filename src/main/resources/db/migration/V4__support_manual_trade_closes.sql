ALTER TABLE trades
    ALTER COLUMN ticket DROP NOT NULL;

ALTER TABLE trade_closes
    ALTER COLUMN import_batch_id DROP NOT NULL,
    DROP CONSTRAINT trade_closes_source_check,
    ADD CONSTRAINT trade_closes_source_check CHECK (source IN ('TRADINGVIEW', 'TRADES_REPORT', 'MANUAL'));
