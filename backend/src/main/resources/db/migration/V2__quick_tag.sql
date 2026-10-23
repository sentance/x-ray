-- Strategy tags for leaderboard wallets that were never synced, classified from a sample of recent activity.
CREATE TABLE quick_tag (
    address     TEXT PRIMARY KEY,
    payload     JSONB       NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
