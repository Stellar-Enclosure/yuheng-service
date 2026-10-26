-- EDGE 独立 OAuth 状态：一次性交互、刷新协调、撤销意图与回执。
-- EDGE-owned one-use OAuth state; no Session, SecurityContext or token vault.

CREATE TABLE edge_oauth_transaction (
    transaction_id UUID PRIMARY KEY,
    target_digest CHAR(64) NOT NULL,
    app_alias VARCHAR(128) NOT NULL,
    registration_id VARCHAR(128) NOT NULL,
    issuer_uri VARCHAR(2048) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    client_id VARCHAR(256) NOT NULL,
    resource_uri VARCHAR(2048) NOT NULL,
    redirect_uri VARCHAR(2048) NOT NULL,
    return_path VARCHAR(2048) NOT NULL,
    requested_scopes TEXT[] NOT NULL,
    state_digest CHAR(64) NOT NULL,
    browser_binding_digest CHAR(64) NOT NULL,
    nonce_digest CHAR(64) NOT NULL,
    code_challenge VARCHAR(43) NOT NULL,
    encryption_key_id VARCHAR(64),
    encryption_version SMALLINT,
    verifier_ciphertext BYTEA,
    nonce_ciphertext BYTEA,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    authorization_code_digest CHAR(64),
    exchange_attempt_id UUID,
    lease_owner UUID,
    lease_expires_at TIMESTAMPTZ,
    exchange_started_at TIMESTAMPTZ,
    exchange_deadline_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    failure_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    retain_until TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_edge_oauth_transaction_state UNIQUE (target_digest, state_digest),
    CONSTRAINT uq_edge_oauth_transaction_exchange UNIQUE (exchange_attempt_id),
    CONSTRAINT ck_edge_oauth_transaction_target CHECK (
        target_digest ~ '^[0-9a-f]{64}$'
        AND app_alias ~ '^[A-Za-z0-9_-]{1,128}$'
        AND registration_id ~ '^[A-Za-z0-9_-]{1,128}$'
        AND issuer_uri LIKE 'https://%'
        AND resource_uri <> '' AND client_id <> '' AND tenant_id <> ''
        AND redirect_uri LIKE 'https://%'
        AND return_path LIKE '/%' AND return_path NOT LIKE '//%'
    ),
    CONSTRAINT ck_edge_oauth_transaction_proofs CHECK (
        state_digest ~ '^[0-9a-f]{64}$'
        AND browser_binding_digest ~ '^[0-9a-f]{64}$'
        AND nonce_digest ~ '^[0-9a-f]{64}$'
        AND code_challenge ~ '^[A-Za-z0-9_-]{43}$'
        AND cardinality(requested_scopes) > 0
        AND array_position(requested_scopes, NULL) IS NULL
        AND array_position(requested_scopes, '') IS NULL
        AND (authorization_code_digest IS NULL OR authorization_code_digest ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_edge_oauth_transaction_status CHECK (
        status IN ('PENDING', 'EXCHANGING', 'SUCCEEDED', 'FAILED', 'EXPIRED', 'CLOSED')
    ),
    CONSTRAINT ck_edge_oauth_transaction_secret_lifecycle CHECK (
        (status = 'PENDING'
            AND encryption_key_id IS NOT NULL AND encryption_key_id ~ '^[A-Za-z0-9._-]{1,64}$'
            AND encryption_version IS NOT NULL AND encryption_version = 1
            AND verifier_ciphertext IS NOT NULL AND octet_length(verifier_ciphertext) BETWEEN 32 AND 2048
            AND nonce_ciphertext IS NOT NULL AND octet_length(nonce_ciphertext) BETWEEN 32 AND 2048)
        OR (status <> 'PENDING'
            AND encryption_key_id IS NULL AND encryption_version IS NULL
            AND verifier_ciphertext IS NULL AND nonce_ciphertext IS NULL)
    ),
    CONSTRAINT ck_edge_oauth_transaction_lease CHECK (
        (status = 'EXCHANGING'
            AND exchange_attempt_id IS NOT NULL AND authorization_code_digest IS NOT NULL
            AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL
            AND exchange_started_at IS NOT NULL AND exchange_deadline_at IS NOT NULL
            AND exchange_started_at < lease_expires_at
            AND lease_expires_at <= exchange_deadline_at
            AND exchange_deadline_at <= expires_at
            AND completed_at IS NULL)
        OR (status <> 'EXCHANGING' AND lease_owner IS NULL AND lease_expires_at IS NULL)
    ),
    CONSTRAINT ck_edge_oauth_transaction_terminal CHECK (
        (status IN ('PENDING', 'EXCHANGING') AND completed_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED', 'EXPIRED', 'CLOSED') AND completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_edge_oauth_transaction_times CHECK (
        version >= 0 AND created_at <= updated_at AND created_at < expires_at
        AND expires_at <= retain_until
        AND (completed_at IS NULL OR completed_at >= created_at)
    )
);

CREATE INDEX ix_edge_oauth_transaction_expiry
    ON edge_oauth_transaction (expires_at, transaction_id) WHERE status = 'PENDING';
CREATE INDEX ix_edge_oauth_transaction_lease
    ON edge_oauth_transaction (lease_expires_at, transaction_id) WHERE status = 'EXCHANGING';
CREATE INDEX ix_edge_oauth_transaction_retention ON edge_oauth_transaction (retain_until);

CREATE TABLE edge_oauth_refresh_operation (
    operation_id UUID PRIMARY KEY,
    target_digest CHAR(64) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    app_alias VARCHAR(128) NOT NULL,
    registration_id VARCHAR(128) NOT NULL,
    issuer_uri VARCHAR(2048) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    client_id VARCHAR(256) NOT NULL,
    resource_uri VARCHAR(2048) NOT NULL,
    subject VARCHAR(256) NOT NULL,
    authorization_id VARCHAR(128) NOT NULL,
    authentication_jti VARCHAR(256) NOT NULL,
    browser_binding_digest CHAR(64) NOT NULL,
    refresh_token_digest CHAR(64) NOT NULL,
    refresh_generation BIGINT NOT NULL,
    authorization_expires_at TIMESTAMPTZ NOT NULL,
    authentication_expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS',
    lease_owner UUID,
    lease_expires_at TIMESTAMPTZ,
    deadline_at TIMESTAMPTZ NOT NULL,
    upstream_attempts SMALLINT NOT NULL DEFAULT 0,
    upstream_started_at TIMESTAMPTZ,
    upstream_completed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    successor_generation BIGINT,
    access_expires_at TIMESTAMPTZ,
    failure_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    retain_until TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_edge_oauth_refresh_credential UNIQUE (target_digest, refresh_token_digest),
    CONSTRAINT ck_edge_oauth_refresh_target CHECK (
        target_digest ~ '^[0-9a-f]{64}$' AND request_digest ~ '^[0-9a-f]{64}$'
        AND app_alias ~ '^[A-Za-z0-9_-]{1,128}$'
        AND registration_id ~ '^[A-Za-z0-9_-]{1,128}$'
        AND issuer_uri LIKE 'https://%'
        AND tenant_id <> '' AND client_id <> '' AND resource_uri <> '' AND subject <> ''
        AND (authorization_id IS NULL OR authorization_id <> '')
        AND (authentication_jti IS NULL OR authentication_jti <> '')
    ),
    CONSTRAINT ck_edge_oauth_refresh_proofs CHECK (
        browser_binding_digest ~ '^[0-9a-f]{64}$'
        AND refresh_token_digest ~ '^[0-9a-f]{64}$'
        AND (refresh_generation IS NULL OR refresh_generation >= 0)
        AND (successor_generation IS NULL OR successor_generation >= 0)
        AND (refresh_generation IS NULL OR successor_generation IS NULL
            OR successor_generation = refresh_generation + 1)
    ),
    CONSTRAINT ck_edge_oauth_refresh_status CHECK (
        status IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'CLOSED')
    ),
    CONSTRAINT ck_edge_oauth_refresh_lease CHECK (
        (status = 'IN_PROGRESS' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL
            AND lease_expires_at > created_at AND lease_expires_at <= deadline_at)
        OR (status <> 'IN_PROGRESS' AND lease_owner IS NULL AND lease_expires_at IS NULL)
    ),
    CONSTRAINT ck_edge_oauth_refresh_single_dispatch CHECK (
        (upstream_attempts = 0 AND upstream_started_at IS NULL AND upstream_completed_at IS NULL)
        OR (upstream_attempts = 1 AND upstream_started_at IS NOT NULL)
    ),
    CONSTRAINT ck_edge_oauth_refresh_terminal CHECK (
        (status = 'IN_PROGRESS' AND completed_at IS NULL AND closed_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN') AND completed_at IS NOT NULL AND closed_at IS NULL)
        OR (status = 'CLOSED' AND completed_at IS NOT NULL AND closed_at IS NOT NULL)
    ),
    CONSTRAINT ck_edge_oauth_refresh_success CHECK (
        (status = 'SUCCEEDED' AND upstream_attempts = 1 AND upstream_completed_at IS NOT NULL
            AND access_expires_at IS NOT NULL)
        OR status <> 'SUCCEEDED'
    ),
    CONSTRAINT ck_edge_oauth_refresh_times CHECK (
        version >= 0 AND created_at <= updated_at AND created_at < deadline_at
        AND deadline_at <= authorization_expires_at AND authorization_expires_at <= retain_until
        AND (completed_at IS NULL OR completed_at >= created_at)
        AND (closed_at IS NULL OR closed_at >= completed_at)
        AND (upstream_completed_at IS NULL OR upstream_completed_at >= upstream_started_at)
    )
);

CREATE UNIQUE INDEX uq_edge_oauth_refresh_generation
    ON edge_oauth_refresh_operation (target_digest, authorization_id, refresh_generation)
    WHERE authorization_id IS NOT NULL AND refresh_generation IS NOT NULL;
CREATE INDEX ix_edge_oauth_refresh_lease
    ON edge_oauth_refresh_operation (lease_expires_at, operation_id) WHERE status = 'IN_PROGRESS';
CREATE INDEX ix_edge_oauth_refresh_authorization
    ON edge_oauth_refresh_operation (target_digest, authorization_id, status);
CREATE INDEX ix_edge_oauth_refresh_authentication
    ON edge_oauth_refresh_operation (authentication_jti, status) WHERE authentication_jti IS NOT NULL;
CREATE INDEX ix_edge_oauth_refresh_retention ON edge_oauth_refresh_operation (retain_until);

CREATE TABLE edge_oauth_revocation_intent (
    intent_id UUID PRIMARY KEY,
    idempotency_digest CHAR(64) NOT NULL,
    target_digest CHAR(64) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    source_kind VARCHAR(24) NOT NULL,
    source_refresh_operation_id UUID,
    source_transaction_id UUID,
    app_alias VARCHAR(128) NOT NULL,
    registration_id VARCHAR(128) NOT NULL,
    issuer_uri VARCHAR(2048) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    client_id VARCHAR(256) NOT NULL,
    resource_uri VARCHAR(2048) NOT NULL,
    subject VARCHAR(256),
    authorization_id VARCHAR(128),
    authentication_jti VARCHAR(256),
    browser_binding_digest CHAR(64) NOT NULL,
    token_digest CHAR(64) NOT NULL,
    token_type_hint VARCHAR(16) NOT NULL DEFAULT 'refresh_token',
    encryption_key_id VARCHAR(64),
    encryption_version SMALLINT,
    token_ciphertext BYTEA,
    status VARCHAR(16) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    lease_owner UUID,
    lease_expires_at TIMESTAMPTZ,
    first_dispatched_at TIMESTAMPTZ,
    last_dispatched_at TIMESTAMPTZ,
    confirmed_at TIMESTAMPTZ,
    terminal_at TIMESTAMPTZ,
    last_http_status SMALLINT,
    failure_code VARCHAR(128),
    authorization_expires_at TIMESTAMPTZ,
    expiry_source VARCHAR(24) NOT NULL,
    dispatch_deadline_at TIMESTAMPTZ NOT NULL,
    deny_until TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    retain_until TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_edge_oauth_revocation_idempotency UNIQUE (target_digest, idempotency_digest),
    CONSTRAINT fk_edge_oauth_revocation_refresh FOREIGN KEY (source_refresh_operation_id)
        REFERENCES edge_oauth_refresh_operation (operation_id) ON DELETE RESTRICT,
    CONSTRAINT fk_edge_oauth_revocation_transaction FOREIGN KEY (source_transaction_id)
        REFERENCES edge_oauth_transaction (transaction_id) ON DELETE RESTRICT,
    CONSTRAINT ck_edge_oauth_revocation_target CHECK (
        target_digest ~ '^[0-9a-f]{64}$' AND request_digest ~ '^[0-9a-f]{64}$'
        AND idempotency_digest ~ '^[0-9a-f]{64}$'
        AND app_alias ~ '^[A-Za-z0-9_-]{1,128}$'
        AND registration_id ~ '^[A-Za-z0-9_-]{1,128}$'
        AND issuer_uri LIKE 'https://%' AND tenant_id <> '' AND client_id <> '' AND resource_uri <> ''
    ),
    CONSTRAINT ck_edge_oauth_revocation_source CHECK (
        (source_kind = 'REFRESH_RECOVERY' AND source_refresh_operation_id IS NOT NULL AND source_transaction_id IS NULL)
        OR (source_kind = 'CODE_RECOVERY' AND source_refresh_operation_id IS NULL AND source_transaction_id IS NOT NULL)
        OR (source_kind IN ('LOGOUT', 'ACCOUNT_SWITCH', 'REPLAY') AND source_refresh_operation_id IS NULL AND source_transaction_id IS NULL)
    ),
    CONSTRAINT ck_edge_oauth_revocation_proofs CHECK (
        browser_binding_digest ~ '^[0-9a-f]{64}$' AND token_digest ~ '^[0-9a-f]{64}$'
        AND token_type_hint = 'refresh_token'
        AND (authorization_id IS NULL OR authorization_id <> '')
        AND (authentication_jti IS NULL OR authentication_jti <> '')
    ),
    CONSTRAINT ck_edge_oauth_revocation_status CHECK (
        status IN ('ARMED', 'PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'DISARMED', 'DEAD_LETTER')
        AND (status <> 'ARMED' OR source_kind = 'REFRESH_RECOVERY')
    ),
    CONSTRAINT ck_edge_oauth_revocation_ciphertext CHECK (
        (status IN ('ARMED', 'PENDING', 'IN_PROGRESS')
            AND encryption_key_id IS NOT NULL AND encryption_key_id ~ '^[A-Za-z0-9._-]{1,64}$'
            AND encryption_version IS NOT NULL AND encryption_version = 1
            AND token_ciphertext IS NOT NULL AND octet_length(token_ciphertext) BETWEEN 32 AND 16384)
        OR (status IN ('SUCCEEDED', 'DISARMED', 'DEAD_LETTER')
            AND encryption_key_id IS NULL AND encryption_version IS NULL AND token_ciphertext IS NULL)
    ),
    CONSTRAINT ck_edge_oauth_revocation_lease CHECK (
        (status = 'IN_PROGRESS' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL
            AND lease_expires_at <= dispatch_deadline_at)
        OR (status <> 'IN_PROGRESS' AND lease_owner IS NULL AND lease_expires_at IS NULL)
    ),
    CONSTRAINT ck_edge_oauth_revocation_terminal CHECK (
        (status IN ('ARMED', 'PENDING', 'IN_PROGRESS') AND terminal_at IS NULL AND confirmed_at IS NULL)
        OR (status = 'SUCCEEDED' AND terminal_at IS NOT NULL AND confirmed_at IS NOT NULL
            AND last_http_status IS NOT NULL AND last_http_status = 200)
        OR (status IN ('DISARMED', 'DEAD_LETTER') AND terminal_at IS NOT NULL AND confirmed_at IS NULL)
    ),
    CONSTRAINT ck_edge_oauth_revocation_expiry_source CHECK (
        (expiry_source = 'VERIFIED_CREDENTIAL' AND authorization_expires_at IS NOT NULL)
        OR (expiry_source = 'CONFIGURED_UPPER_BOUND' AND authorization_expires_at IS NULL)
    ),
    CONSTRAINT ck_edge_oauth_revocation_times CHECK (
        version >= 0 AND attempts >= 0 AND created_at <= updated_at
        AND created_at < dispatch_deadline_at AND dispatch_deadline_at <= deny_until
        AND (authorization_expires_at IS NULL OR authorization_expires_at <= deny_until)
        AND deny_until <= retain_until
        AND (terminal_at IS NULL OR terminal_at >= created_at)
        AND (last_http_status IS NULL OR last_http_status BETWEEN 100 AND 599)
    )
);

CREATE UNIQUE INDEX uq_edge_oauth_revocation_refresh
    ON edge_oauth_revocation_intent (source_refresh_operation_id)
    WHERE source_refresh_operation_id IS NOT NULL;
CREATE INDEX ix_edge_oauth_revocation_due
    ON edge_oauth_revocation_intent (next_attempt_at, intent_id) WHERE status = 'PENDING';
CREATE INDEX ix_edge_oauth_revocation_lease
    ON edge_oauth_revocation_intent (lease_expires_at, intent_id) WHERE status = 'IN_PROGRESS';
CREATE INDEX ix_edge_oauth_revocation_deadline
    ON edge_oauth_revocation_intent (dispatch_deadline_at, intent_id)
    WHERE status IN ('ARMED', 'PENDING', 'IN_PROGRESS');
CREATE INDEX ix_edge_oauth_revocation_authorization_deny
    ON edge_oauth_revocation_intent (target_digest, authorization_id, deny_until)
    WHERE status IN ('PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'DEAD_LETTER') AND authorization_id IS NOT NULL;
CREATE INDEX ix_edge_oauth_revocation_authentication_deny
    ON edge_oauth_revocation_intent (authentication_jti, deny_until)
    WHERE status IN ('PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'DEAD_LETTER') AND authentication_jti IS NOT NULL;
CREATE INDEX ix_edge_oauth_revocation_credential_deny
    ON edge_oauth_revocation_intent (target_digest, token_digest, deny_until)
    WHERE status IN ('PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'DEAD_LETTER');
CREATE INDEX ix_edge_oauth_revocation_retention ON edge_oauth_revocation_intent (retain_until);

CREATE TABLE edge_oauth_revocation_receipt (
    receipt_id UUID PRIMARY KEY,
    intent_id UUID NOT NULL,
    target_digest CHAR(64) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    token_digest CHAR(64) NOT NULL,
    attempt_number INTEGER NOT NULL,
    http_status SMALLINT NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    retain_until TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_edge_oauth_revocation_receipt_intent UNIQUE (intent_id),
    CONSTRAINT fk_edge_oauth_revocation_receipt_intent FOREIGN KEY (intent_id)
        REFERENCES edge_oauth_revocation_intent (intent_id) ON DELETE RESTRICT,
    CONSTRAINT ck_edge_oauth_revocation_receipt_proof CHECK (
        target_digest ~ '^[0-9a-f]{64}$' AND request_digest ~ '^[0-9a-f]{64}$'
        AND token_digest ~ '^[0-9a-f]{64}$' AND attempt_number >= 1 AND http_status = 200
        AND confirmed_at <= created_at AND created_at <= retain_until
    )
);

CREATE INDEX ix_edge_oauth_revocation_receipt_retention ON edge_oauth_revocation_receipt (retain_until);
