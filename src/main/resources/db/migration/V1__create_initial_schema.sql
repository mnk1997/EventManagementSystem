CREATE TABLE app_user (
    id              UUID PRIMARY KEY,
    email           VARCHAR(320) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(32) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_role CHECK (role IN ('EVENT_ORGANIZER', 'CUSTOMER'))
);

CREATE TABLE event (
    id                  UUID PRIMARY KEY,
    organizer_id        UUID NOT NULL REFERENCES app_user(id),
    name                VARCHAR(200) NOT NULL,
    description         TEXT NOT NULL,
    starts_at           TIMESTAMPTZ NOT NULL,
    venue               VARCHAR(300) NOT NULL,
    location            VARCHAR(200) NOT NULL,
    capacity            INTEGER NOT NULL,
    booked_tickets      INTEGER NOT NULL DEFAULT 0,
    status              VARCHAR(24) NOT NULL DEFAULT 'PUBLISHED',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_event_capacity CHECK (capacity > 0),
    CONSTRAINT ck_event_booked_tickets CHECK (booked_tickets >= 0 AND booked_tickets <= capacity),
    CONSTRAINT ck_event_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED', 'COMPLETED'))
);

CREATE INDEX ix_event_starts_at ON event (starts_at);
CREATE INDEX ix_event_location_starts_at ON event (location, starts_at);
CREATE INDEX ix_event_organizer ON event (organizer_id);
CREATE INDEX ix_event_status_starts_at ON event (status, starts_at);

CREATE TABLE booking (
    id              UUID PRIMARY KEY,
    event_id        UUID NOT NULL REFERENCES event(id),
    customer_id     UUID NOT NULL REFERENCES app_user(id),
    ticket_quantity INTEGER NOT NULL,
    status          VARCHAR(24) NOT NULL DEFAULT 'CONFIRMED',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at    TIMESTAMPTZ,
    CONSTRAINT ck_booking_ticket_quantity CHECK (ticket_quantity > 0),
    CONSTRAINT ck_booking_status CHECK (status IN ('CONFIRMED', 'CANCELLED'))
);

CREATE INDEX ix_booking_customer_created ON booking (customer_id, created_at DESC);
CREATE INDEX ix_booking_event_status ON booking (event_id, status);
CREATE INDEX ix_booking_customer_event_status ON booking (customer_id, event_id, status);

CREATE TABLE idempotency_record (
    id              UUID PRIMARY KEY,
    customer_id     UUID NOT NULL REFERENCES app_user(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    VARCHAR(64) NOT NULL,
    booking_id      UUID REFERENCES booking(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_idempotency_customer_key UNIQUE (customer_id, idempotency_key)
);

CREATE INDEX ix_idempotency_created_at ON idempotency_record (created_at);

CREATE TABLE outbox_job (
    id              UUID PRIMARY KEY,
    deduplication_key VARCHAR(160) NOT NULL UNIQUE,
    job_type        VARCHAR(80) NOT NULL,
    aggregate_type  VARCHAR(40) NOT NULL,
    aggregate_id    UUID NOT NULL,
    payload         JSONB NOT NULL,
    status          VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER NOT NULL DEFAULT 0,
    available_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_until    TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMPTZ,
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'DEAD'))
);

CREATE INDEX ix_outbox_claim ON outbox_job (status, available_at, created_at);
CREATE INDEX ix_outbox_aggregate ON outbox_job (aggregate_type, aggregate_id);
