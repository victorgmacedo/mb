CREATE TABLE IF NOT EXISTS engine_state_guard (
    id INTEGER PRIMARY KEY CHECK (id=1),
    initialized BOOLEAN NOT NULL,
    last_sequence BIGINT NOT NULL CHECK (last_sequence >= 0)
);
CREATE TABLE IF NOT EXISTS engine_partition_ownership (
    topic TEXT NOT NULL,
    partition_id INTEGER NOT NULL,
    owner TEXT,
    epoch BIGINT NOT NULL CHECK (epoch > 0),
    PRIMARY KEY(topic,partition_id)
);
CREATE TABLE IF NOT EXISTS engine_durable_books (
    instrument TEXT PRIMARY KEY,
    payload BYTEA NOT NULL
);
CREATE TABLE IF NOT EXISTS engine_commands (
    id TEXT PRIMARY KEY,
    topic TEXT NOT NULL,
    partition_id INTEGER NOT NULL,
    source_offset BIGINT NOT NULL,
    message_key TEXT NOT NULL,
    business_id TEXT UNIQUE,
    payload TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('APPLIED','REJECTED','DUPLICATE')),
    result TEXT NOT NULL,
    owner_epoch BIGINT NOT NULL,
    duplicate_of TEXT REFERENCES engine_commands(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(topic,partition_id,source_offset)
);
CREATE TABLE IF NOT EXISTS engine_outbox (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id TEXT NOT NULL UNIQUE,
    topic TEXT NOT NULL,
    message_key TEXT NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS engine_outbox_pending ON engine_outbox(id) WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS engine_legacy_order_ids (
    client_order_id TEXT PRIMARY KEY
);
