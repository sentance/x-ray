CREATE TABLE wallet (
    address          TEXT PRIMARY KEY,
    name             TEXT,
    pseudonym        TEXT,
    profile          JSONB,
    newest_ts        BIGINT,
    oldest_ts        BIGINT,
    activity_count   BIGINT  NOT NULL DEFAULT 0,
    history_complete BOOLEAN NOT NULL DEFAULT FALSE,
    last_synced_at   TIMESTAMPTZ,
    sync_version     INT     NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sync_job (
    id          BIGSERIAL PRIMARY KEY,
    address     TEXT        NOT NULL REFERENCES wallet (address),
    status      TEXT        NOT NULL,
    phase       TEXT,
    fetched     BIGINT      NOT NULL DEFAULT 0,
    expected    BIGINT,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    error       TEXT
);
CREATE INDEX sync_job_address_idx ON sync_job (address, id DESC);

CREATE TABLE activity (
    id            BIGSERIAL PRIMARY KEY,
    address       TEXT    NOT NULL,
    ts            BIGINT  NOT NULL,
    type          TEXT    NOT NULL,
    condition_id  TEXT,
    asset         TEXT,
    side          TEXT,
    size          NUMERIC,
    usdc_size     NUMERIC,
    price         NUMERIC,
    outcome       TEXT,
    outcome_index INT,
    tx_hash       TEXT,
    slug          TEXT,
    event_slug    TEXT,
    title         TEXT,
    dedupe_key    TEXT    NOT NULL,
    UNIQUE (address, dedupe_key)
);
CREATE INDEX activity_address_ts_idx ON activity (address, ts);
CREATE INDEX activity_address_condition_idx ON activity (address, condition_id);

CREATE TABLE market (
    condition_id          TEXT PRIMARY KEY,
    slug                  TEXT,
    event_slug            TEXT,
    title                 TEXT,
    category              TEXT,
    tags                  TEXT[],
    end_date              TIMESTAMPTZ,
    closed                BOOLEAN,
    winning_outcome_index INT,
    neg_risk              BOOLEAN,
    enriched_at           TIMESTAMPTZ
);
CREATE INDEX market_event_slug_idx ON market (event_slug);

CREATE TABLE pnl_point (
    address        TEXT   NOT NULL,
    ts             BIGINT NOT NULL,
    economic_pnl   NUMERIC,
    realized_pnl   NUMERIC,
    unrealized_pnl NUMERIC,
    trade_pnl      NUMERIC,
    fees           NUMERIC,
    maker_rebate   NUMERIC,
    taker_rebate   NUMERIC,
    reward_income  NUMERIC,
    volume_usdc    NUMERIC,
    trade_count    BIGINT,
    PRIMARY KEY (address, ts)
);

CREATE TABLE position_snapshot (
    address    TEXT PRIMARY KEY,
    open_value NUMERIC,
    positions  JSONB       NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE binance_kline (
    symbol    TEXT    NOT NULL,
    open_time BIGINT  NOT NULL,
    open      NUMERIC NOT NULL,
    close     NUMERIC NOT NULL,
    PRIMARY KEY (symbol, open_time)
);

CREATE TABLE analysis_cache (
    address      TEXT  NOT NULL,
    kind         TEXT  NOT NULL,
    sync_version INT   NOT NULL,
    payload      JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (address, kind)
);

CREATE TABLE ai_narrative (
    address      TEXT PRIMARY KEY,
    sync_version INT  NOT NULL,
    text         TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
