ALTER TABLE trades
    ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE INDEX trades_user_account_deleted_idx
    ON trades (user_id, trading_account_id, deleted_at);
