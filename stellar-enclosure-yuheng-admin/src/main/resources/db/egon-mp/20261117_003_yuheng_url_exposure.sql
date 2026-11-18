-- 20261117_003: authoritative URL exposure control metadata.
-- Route as STRATEGY SINGLE to the same control PRIMARY as release/idempotency/audit/Outbox.
-- Exact physical bindings are globally reserved across owners, including CLOSED/WITHDRAWN rows.
-- Semantic wildcard/method/path-prefix overlaps require the publication-domain transaction lock
-- and a complete application-side overlap check; these exact-key indexes do not prove non-overlap.
CREATE TABLE public.gateway_url_exposure_t0 (
    id                      bigint          NOT NULL,
    tenant_id               bigint          NOT NULL,
    create_user_id          varchar(128)    NOT NULL,
    create_time             timestamptz(6)  NOT NULL,
    update_user_id          varchar(128)    NOT NULL,
    update_time             timestamptz(6)  NOT NULL,
    deleted_at              timestamp(6),
    version                 bigint          NOT NULL DEFAULT 0,
    owner_tenant_id         bigint          NOT NULL,
    gateway_group_id        bigint          NOT NULL,
    route_id                varchar(128)    NOT NULL,
    cluster_id              varchar(128)    NOT NULL,
    gateway_namespace       varchar(63)     NOT NULL,
    gateway_name            varchar(253)    NOT NULL,
    gateway_uid             varchar(128)    NOT NULL,
    listener_name           varchar(253)    NOT NULL,
    hostname                varchar(253)    NOT NULL,
    http_method             varchar(16)     NOT NULL,
    path_match_type         varchar(16)     NOT NULL,
    path_value              varchar(1024)   NOT NULL,
    route_kind              varchar(16)     NOT NULL,
    route_namespace         varchar(63)     NOT NULL,
    route_name              varchar(63)     NOT NULL,
    route_uid               varchar(128),
    desired_state           varchar(16)     NOT NULL,
    desired_generation      bigint          NOT NULL DEFAULT 1,
    observed_state          varchar(16)     NOT NULL DEFAULT 'UNKNOWN',
    observed_generation     bigint          NOT NULL DEFAULT 0,
    reconcile_status        varchar(16)     NOT NULL DEFAULT 'PENDING',
    release_id              bigint          NOT NULL,
    content_sha256          char(64)        NOT NULL,
    compiler_profile_id     varchar(128)    NOT NULL,
    observation             jsonb           NOT NULL DEFAULT
        jsonb_build_object('schemaVersion', 1, 'conditions', '[]'::jsonb, 'kubernetesRoute', NULL),
    last_error_code         varchar(128),
    change_reason           varchar(1024)   NOT NULL,
    withdrawn_at            timestamptz(6),

    CONSTRAINT pk_url_exposure PRIMARY KEY (id),
    CONSTRAINT ck_url_exposure_technical_tenant CHECK (tenant_id = 0),
    CONSTRAINT ck_url_exposure_owner_tenant CHECK (owner_tenant_id > 0),
    CONSTRAINT ck_url_exposure_version CHECK (version >= 0),
    CONSTRAINT ck_url_exposure_permanent_tombstone CHECK (deleted_at IS NULL),
    CONSTRAINT ck_url_exposure_cluster_ascii CHECK (octet_length(cluster_id) = length(cluster_id)),
    CONSTRAINT ck_url_exposure_cluster_nonblank CHECK (length(btrim(cluster_id)) > 0),
    CONSTRAINT ck_url_exposure_gateway_namespace
        CHECK (gateway_namespace ~ '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'),
    CONSTRAINT ck_url_exposure_gateway_name
        CHECK (gateway_name ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$'),
    CONSTRAINT ck_url_exposure_gateway_uid CHECK (length(btrim(gateway_uid)) > 0),
    CONSTRAINT ck_url_exposure_listener_name
        CHECK (listener_name ~ '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'),
    CONSTRAINT ck_url_exposure_hostname_ascii CHECK (octet_length(hostname) = length(hostname)),
    CONSTRAINT ck_url_exposure_hostname
        CHECK (hostname ~ '^(\*\.)?[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$'),
    CONSTRAINT ck_url_exposure_http_method
        CHECK (http_method = '*' OR http_method ~ '^[A-Z][A-Z0-9!#$%&''*+.^_`|~-]{0,15}$'),
    CONSTRAINT ck_url_exposure_path_match_type CHECK (path_match_type IN ('EXACT', 'PATH_PREFIX')),
    CONSTRAINT ck_url_exposure_path
        CHECK (left(path_value, 1) = '/' AND octet_length(path_value) <= 1024
            AND octet_length(path_value) = length(path_value) AND path_value !~ '[[:cntrl:][:space:]]'),
    CONSTRAINT ck_url_exposure_route_kind CHECK (route_kind = 'HTTPRoute'),
    CONSTRAINT ck_url_exposure_route_namespace
        CHECK (route_namespace ~ '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'),
    CONSTRAINT ck_url_exposure_route_name
        CHECK (route_name ~ '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'),
    CONSTRAINT ck_url_exposure_route_uid CHECK (route_uid IS NULL OR length(btrim(route_uid)) > 0),
    CONSTRAINT ck_url_exposure_desired_state CHECK (desired_state IN ('OPEN', 'CLOSED', 'WITHDRAWN')),
    CONSTRAINT ck_url_exposure_desired_generation CHECK (desired_generation >= 1),
    CONSTRAINT ck_url_exposure_observed_state CHECK (observed_state IN ('UNKNOWN', 'OPEN', 'CLOSED', 'WITHDRAWN')),
    CONSTRAINT ck_url_exposure_observed_generation
        CHECK (observed_generation >= 0 AND observed_generation <= desired_generation),
    CONSTRAINT ck_url_exposure_reconcile_status CHECK (reconcile_status IN ('PENDING', 'RECONCILING', 'SYNCED', 'FAILED')),
    CONSTRAINT ck_url_exposure_synced
        CHECK (reconcile_status <> 'SYNCED' OR (observed_generation = desired_generation AND observed_state = desired_state)),
    CONSTRAINT ck_url_exposure_content_sha CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_url_exposure_compiler_profile CHECK (length(btrim(compiler_profile_id)) > 0),
    CONSTRAINT ck_url_exposure_observation
        CHECK (jsonb_typeof(observation) = 'object' AND octet_length(observation::text) <= 65536),
    CONSTRAINT ck_url_exposure_change_reason CHECK (length(btrim(change_reason)) > 0),
    CONSTRAINT ck_url_exposure_withdrawn CHECK ((desired_state = 'WITHDRAWN') = (withdrawn_at IS NOT NULL)),
    CONSTRAINT uq_url_exposure_route_life UNIQUE (owner_tenant_id, gateway_group_id, route_id, deleted_at),
    CONSTRAINT uq_url_exposure_binding_life UNIQUE
        (cluster_id, gateway_namespace, gateway_name, listener_name, hostname, http_method, path_match_type, path_value, deleted_at),
    CONSTRAINT fk_url_exposure_group FOREIGN KEY (owner_tenant_id, gateway_group_id)
        REFERENCES public.gateway_group_t0 (tenant_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_url_exposure_release FOREIGN KEY (owner_tenant_id, release_id)
        REFERENCES public.gateway_release_t0 (tenant_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_url_exposure_route_active
    ON public.gateway_url_exposure_t0 (owner_tenant_id, gateway_group_id, route_id)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX uq_url_exposure_binding_active
    ON public.gateway_url_exposure_t0
        (cluster_id, gateway_namespace, gateway_name, listener_name, hostname, http_method, path_match_type, path_value)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX uq_url_exposure_route_object
    ON public.gateway_url_exposure_t0 (cluster_id, route_namespace, route_name);
