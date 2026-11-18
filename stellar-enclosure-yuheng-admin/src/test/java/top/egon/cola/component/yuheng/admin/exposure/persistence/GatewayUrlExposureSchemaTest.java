package top.egon.cola.component.yuheng.admin.exposure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import top.egon.cola.component.yuheng.admin.bootstrap.GatewayAdminApplication;
import top.egon.cola.component.yuheng.admin.exposure.dao.GatewayUrlExposureDAO;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** Managed-script contract checks; actual PostgreSQL constraints and transactions are exercised by the IT. */
class GatewayUrlExposureSchemaTest {

    private static final String ROOT = "db/egon-mp/";
    private static final String SCRIPT = "20261117_003_yuheng_url_exposure.sql";
    private static final List<String> VERSIONS = List.of("20260922_001", "20261116_002", "20261117_003");
    private static final List<String> PATHS = List.of(ROOT + "20260922_001_yuheng_schema.sql",
            ROOT + "20261116_002_yuheng_llm_cache.sql", ROOT + SCRIPT);
    private static final List<String> FROZEN_PREFIX = List.of(
            "22dc8ec627ee6e0eaabb1c8ecad55f80798a5d061abcc96a964966bf74de493e",
            "44024a6466e59905ad27c5c35b38c47d573688f935b18602bc648824687987e7");

    @Test
    void applicationRegistersTheExactExposureMapperPackage() {
        MapperScan mapperScan = GatewayAdminApplication.class.getAnnotation(MapperScan.class);
        assertThat(mapperScan).isNotNull();
        assertThat(mapperScan.basePackages()).contains(GatewayUrlExposureDAO.class.getPackageName());
    }

    @Test
    void managed003AndManifestPreserveTheExistingImmutablePrefix() throws Exception {
        assertThat(resource(ROOT + SCRIPT)).isNotEmpty();
        JsonNode manifest = new ObjectMapper().readTree(resource(ROOT + "repository-manifest.json"));
        assertThat(manifest.path("family").asText()).isEqualTo("web");
        JsonNode scripts = manifest.path("scripts");
        assertThat(scripts.isArray()).isTrue();
        assertThat(scripts.size()).isEqualTo(3);
        for (int index = 0; index < VERSIONS.size(); index++) {
            JsonNode entry = scripts.get(index);
            assertThat(entry.path("version").asText()).isEqualTo(VERSIONS.get(index));
            assertThat(entry.path("path").asText()).isEqualTo(PATHS.get(index));
            String actualDigest = digest(resource(PATHS.get(index)));
            assertThat(entry.path("sha256").asText()).isEqualTo(actualDigest);
            if (index < FROZEN_PREFIX.size()) {
                assertThat(actualDigest).isEqualTo(FROZEN_PREFIX.get(index));
            }
        }
    }

    @Test
    void controlTableUsesTypedTenantStateAndBoundedObservationColumns() throws IOException {
        String sql = sql();
        assertThat(sql).contains("create table public.gateway_url_exposure_t0 (");
        assertThat(sql).contains("tenant_id bigint not null", "owner_tenant_id bigint not null",
                "desired_generation bigint not null default 1", "observed_generation bigint not null default 0",
                "version bigint not null default 0", "create_time timestamptz(6) not null",
                "update_time timestamptz(6) not null", "deleted_at timestamp(6)",
                "route_uid varchar(128)", "route_kind varchar(16) not null",
                "compiler_profile_id varchar(128) not null", "observation jsonb not null",
                "withdrawn_at timestamptz(6)");
        assertThat(sql).contains("check (tenant_id = 0)", "check (owner_tenant_id > 0)",
                "check (version >= 0)", "check (deleted_at is null)",
                "check (desired_generation >= 1)",
                "check (observed_generation >= 0 and observed_generation <= desired_generation)",
                "check (route_kind = 'httproute')",
                "check (jsonb_typeof(observation) = 'object' and octet_length(observation::text) <= 65536)",
                "check (content_sha256 ~ '^[0-9a-f]{64}$')");
        assertThat(sql).contains("'schemaversion'", "'kubernetesroute'", "'conditions'");
        assertThat(sql).doesNotContain("drop table", "alter table", "create extension", "'grpcroute'");
    }

    @Test
    void stateChecksRetainPermanentTombstonesAndIndependentObservedGenerations() throws IOException {
        String sql = sql();
        assertThat(sql).contains("desired_state in ('open', 'closed', 'withdrawn')",
                "observed_state in ('unknown', 'open', 'closed', 'withdrawn')",
                "reconcile_status in ('pending', 'reconciling', 'synced', 'failed')",
                "(desired_state = 'withdrawn') = (withdrawn_at is not null)",
                "reconcile_status <> 'synced' or (observed_generation = desired_generation and observed_state = desired_state)",
                "check (length(btrim(change_reason)) > 0)");
        assertThat(sql).doesNotContain("default 'open'", "on delete cascade", "on update cascade");
    }

    @Test
    void lifecycleAndActiveKeysReserveBindingsAcrossOwnersAndStableObjectsForever() throws IOException {
        String sql = sql();
        String binding = "cluster_id, gateway_namespace, gateway_name, listener_name, hostname, http_method, path_match_type, path_value";
        assertThat(sql).contains("constraint pk_url_exposure primary key (id)",
                "constraint uq_url_exposure_route_life unique (owner_tenant_id, gateway_group_id, route_id, deleted_at)",
                "constraint uq_url_exposure_binding_life unique (" + binding + ", deleted_at)",
                "create unique index uq_url_exposure_route_active on public.gateway_url_exposure_t0 (owner_tenant_id, gateway_group_id, route_id) where deleted_at is null",
                "create unique index uq_url_exposure_binding_active on public.gateway_url_exposure_t0 (" + binding + ") where deleted_at is null",
                "create unique index uq_url_exposure_route_object on public.gateway_url_exposure_t0 (cluster_id, route_namespace, route_name)");
        assertThat(sql).contains("foreign key (owner_tenant_id, gateway_group_id) references public.gateway_group_t0 (tenant_id, id) on update restrict on delete restrict",
                "foreign key (owner_tenant_id, release_id) references public.gateway_release_t0 (tenant_id, id) on update restrict on delete restrict");
        assertThat(sql).contains("check (path_match_type in ('exact', 'path_prefix'))",
                "check (http_method = '*' or http_method ~ '^[a-z][a-z0-9!#$%&''*+.^_`|~-]{0,15}$')",
                "octet_length(path_value) <= 1024", "left(path_value, 1) = '/'",
                "check (octet_length(cluster_id) = length(cluster_id))",
                "check (octet_length(hostname) = length(hostname))");
    }

    private static String sql() throws IOException {
        return new String(resource(ROOT + SCRIPT), StandardCharsets.UTF_8)
                .replaceAll("(?m)--[^\\r\\n]*", "").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream stream = GatewayUrlExposureSchemaTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as("required managed DDL resource %s", path).isNotNull();
            return stream.readAllBytes();
        }
    }

    private static String digest(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
