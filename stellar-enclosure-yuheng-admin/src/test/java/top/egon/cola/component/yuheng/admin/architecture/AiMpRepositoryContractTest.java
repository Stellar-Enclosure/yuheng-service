package top.egon.cola.component.yuheng.admin.architecture;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.annotation.TableName;
import org.apache.ibatis.type.TypeHandler;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.common.mybatis.extension.EgonColaExplicitTenantScopeMapper;
import top.egon.cola.component.common.mybatis.extension.EgonColaMapper;
import top.egon.cola.component.common.mybatis.model.EgonModel;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Fixes the Step 3 persistence boundary: every migrated table has one MyBatis-Plus
 * row model on the starter base type, one guarded mapper XML, and no bypass of the
 * starter's tenant/soft-delete guards. Step 23 adds exactly one fixed-zero SINGLE
 * control table, whose explicit owner predicates have their own contract below.
 */
class AiMpRepositoryContractTest {

    /** The 40 managed tables of the migrated admin persistence, per the Plan. */
    private static final List<String> MP_ROW_MODELS = List.of(
            "top.egon.cola.component.yuheng.admin.application.domain.po.GatewayApplicationRecordPO",
            "top.egon.cola.component.yuheng.admin.catalog.domain.po.GatewayBusinessDomainPO",
            "top.egon.cola.component.yuheng.admin.catalog.domain.po.GatewayEntityDomainPO",
            "top.egon.cola.component.yuheng.admin.catalog.domain.po.GatewayInterfaceGroupPO",
            "top.egon.cola.component.yuheng.admin.catalog.domain.po.GatewayOperationDefinitionRecordPO",
            "top.egon.cola.component.yuheng.admin.catalog.domain.po.GatewayOperationRecordPO",
            "top.egon.cola.component.yuheng.admin.credential.domain.po.GatewayCredentialRecordPO",
            "top.egon.cola.component.yuheng.admin.group.domain.po.GatewayGroupRecordPO",
            "top.egon.cola.component.yuheng.admin.llm.domain.po.LlmChannelPO",
            "top.egon.cola.component.yuheng.admin.llm.domain.po.LlmModelPO",
            "top.egon.cola.component.yuheng.admin.mcp.domain.po.McpAppBindingDraftPO",
            "top.egon.cola.component.yuheng.admin.mcp.domain.po.McpArtifactMetadataRecordPO",
            "top.egon.cola.component.yuheng.admin.mcp.domain.po.McpManagedToolOverrideRecordPO",
            "top.egon.cola.component.yuheng.admin.mcp.domain.po.McpPromptDraftPO",
            "top.egon.cola.component.yuheng.admin.mcp.domain.po.McpRemoteCapabilityRecordPO",
            "top.egon.cola.component.yuheng.admin.mcp.domain.po.McpRemoteMountDraftRecordPO",
            "top.egon.cola.component.yuheng.admin.mcp.domain.po.McpRemoteProviderDraftRecordPO",
            "top.egon.cola.component.yuheng.admin.mcp.domain.po.McpRemoteToolDraftRecordPO",
            "top.egon.cola.component.yuheng.admin.mcp.domain.po.McpResourceDraftPO",
            "top.egon.cola.component.yuheng.admin.mcp.domain.po.McpResourceTemplateDraftPO",
            "top.egon.cola.component.yuheng.admin.mcp.domain.po.McpServerRecordPO",
            "top.egon.cola.component.yuheng.admin.mcp.domain.po.McpTaskPolicyDraftPO",
            "top.egon.cola.component.yuheng.admin.observability.domain.po.GatewayAuditLogRecordPO",
            "top.egon.cola.component.yuheng.admin.observability.domain.po.GatewayCallEventSummaryPO",
            "top.egon.cola.component.yuheng.admin.observability.domain.po.GatewayCallMetricMinutePO",
            "top.egon.cola.component.yuheng.admin.observability.domain.po.GatewayConsumeFailureRecordPO",
            "top.egon.cola.component.yuheng.admin.openapi.domain.po.GatewayOpenApiSnapshotRecordPO",
            "top.egon.cola.component.yuheng.admin.openapi.domain.po.GatewayOpenApiSyncRecordPO",
            "top.egon.cola.component.yuheng.admin.release.domain.po.GatewayReleaseAttemptRecordPO",
            "top.egon.cola.component.yuheng.admin.release.domain.po.GatewayReleaseContentPO",
            "top.egon.cola.component.yuheng.admin.release.domain.po.GatewayReleasePublicationRecordPO",
            "top.egon.cola.component.yuheng.admin.release.domain.po.GatewayReleaseRecordPO",
            "top.egon.cola.component.yuheng.admin.release.domain.po.GatewayReleaseTargetRecordPO",
            "top.egon.cola.component.yuheng.admin.reporting.domain.po.GatewayDefinitionSetOperationPO",
            "top.egon.cola.component.yuheng.admin.reporting.domain.po.GatewayDefinitionSetPO",
            "top.egon.cola.component.yuheng.admin.reporting.domain.po.GatewayHmacNoncePO",
            "top.egon.cola.component.yuheng.admin.routing.domain.po.GatewayDraftRecordPO",
            "top.egon.cola.component.yuheng.admin.routing.domain.po.GatewayPolicyDraftRecordPO",
            "top.egon.cola.component.yuheng.admin.routing.domain.po.GatewayRouteDraftRecordPO",
            "top.egon.cola.component.yuheng.admin.shared.domain.po.IdempotencyRecordPO"
    );

    private static final Path MAPPER_XML_ROOT =
            Path.of("src/main/resources/mybatis/mapper");

    private static final Path EXPOSURE_XML = MAPPER_XML_ROOT.resolve("exposure/GatewayUrlExposureDAO.xml");
    private static final String EXPOSURE_MAPPER =
            "top.egon.cola.component.yuheng.admin.exposure.dao.GatewayUrlExposureDAO";
    private static final String EXPOSURE_MODEL =
            "top.egon.cola.component.yuheng.admin.exposure.domain.po.GatewayUrlExposurePO";
    private static final String EXPOSURE_TABLE = "public.gateway_url_exposure_t0";

    private static final Pattern NAMESPACE = Pattern.compile(
            "<mapper\\s+namespace\\s*=\\s*\"([^\"]+)\"");

    private static final Pattern MUTATING_STATEMENT = Pattern.compile(
            "<(update|delete)\\s+id\\s*=\\s*\"([^\"]+)\"");

    @Test
    void everyMigratedRowModelExtendsTheStarterModelAndMapsOneTable() {
        assertEquals(40, MP_ROW_MODELS.size(),
                "the migrated persistence covers 40 tables");
        List<String> missing = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        Map<String, String> tableOwners = new LinkedHashMap<>();
        for (String rowModel : MP_ROW_MODELS) {
            Class<?> type;
            try {
                type = Class.forName(rowModel);
            } catch (ClassNotFoundException absent) {
                missing.add(rowModel);
                continue;
            }
            if (!EgonModel.class.isAssignableFrom(getSuperclass(type, violations, rowModel))) {
                violations.add(rowModel + " does not extend the starter EgonModel");
            }
            TableName tableName = type.getDeclaredAnnotation(TableName.class);
            if (tableName == null) {
                violations.add(rowModel + " declares no @TableName");
                continue;
            }
            String table = tableName.value();
            if (!table.matches("^[a-z][a-z0-9_]*$")) {
                violations.add(rowModel + " maps a non snake_case table " + table);
            }
            String previous = tableOwners.put(table, rowModel);
            if (previous != null) {
                violations.add(table + " is mapped twice: " + previous + " and " + rowModel);
            }
            if (type.isRecord()) {
                violations.add(rowModel + " must be an ordinary class, not a record");
            }
            String source = readRowModelSource(type);
            if (source.contains("jakarta.persistence") || source.contains("javax.persistence")) {
                violations.add(rowModel + " still carries JPA mapping annotations");
            }
            if (source.contains("java.util.Date") || source.contains("java.util.Calendar")) {
                violations.add(rowModel + " must use java.time, not java.util date types");
            }
            if (!source.contains("@SuperBuilder") && !source.contains("@Builder")) {
                violations.add(rowModel + " declares no builder");
            }
            if (!source.contains("@EqualsAndHashCode(callSuper = true)")) {
                violations.add(rowModel + " must compare on the inherited base state");
            }
            if (source.replaceAll("(?s)/\\*.*?\\*/", "")
                    .replaceAll("(?m)//.*$", "")
                    .lines().noneMatch(line -> line.startsWith("    private "))) {
                violations.add(rowModel + " declares no own persisted column");
            }
        }
        assertTrue(missing.isEmpty(),
                "MP row models are missing (Step 3 not implemented): " + missing);
        assertTrue(violations.isEmpty(), "Row model boundary violations: " + violations);
        assertEquals(40, tableOwners.size(), "each table is owned by exactly one row model");
    }

    @Test
    void everyGuardedMapperXmlResolvesToItsAccessInterfaceAndFiltersActiveRows()
            throws IOException {
        List<Path> xmlFiles = mapperXmlFiles();
        assertEquals(41, xmlFiles.size(),
                "40 ordinary mapper XMLs plus the fixed technical exposure mapper");
        assertTrue(xmlFiles.contains(EXPOSURE_XML), "the 41st mapper must be the exact exposure mapper");
        List<Path> ordinaryXmlFiles = xmlFiles.stream().filter(xml -> !xml.equals(EXPOSURE_XML)).toList();
        assertEquals(40, ordinaryXmlFiles.size(), "the original ordinary persistence inventory stays at 40");
        List<String> violations = new ArrayList<>();
        for (Path xml : ordinaryXmlFiles) {
            String text = Files.readString(xml);
            Matcher namespace = NAMESPACE.matcher(text);
            if (!namespace.find()) {
                violations.add(xml + " has no <mapper namespace>");
                continue;
            }
            String interfaceName = namespace.group(1);
            if (!Files.isRegularFile(sourceOf(interfaceName))) {
                violations.add(interfaceName + " has no interface source for " + xml);
            }
            for (String statement : statements(text, "select")) {
                if (!statement.contains("deleted_at IS NULL")) {
                    violations.add(xml + " has a select without the active "
                            + "deleted_at predicate: " + statement.strip());
                }
            }
            for (String statement : statements(text, "update", "delete", "insert")) {
                if (!statement.toLowerCase().contains("tenant_id")) {
                    violations.add(xml + " has a write statement without the "
                            + "tenant column: " + statement.strip());
                }
            }
            for (String handler : typeHandlers(text)) {
                if (!Files.isRegularFile(sourceOf(handler))) {
                    violations.add(xml + " binds a missing type handler " + handler);
                }
            }
        }
        assertTrue(violations.isEmpty(), "Mapper XML boundary violations: " + violations);
    }

    @Test
    void exposureIsTheOnlyFixedTechnicalMapperAndKeepsOwnerAndActivePredicates()
            throws ReflectiveOperationException, IOException {
        Class<?> model = Class.forName(EXPOSURE_MODEL);
        assertTrue(EgonModel.class.isAssignableFrom(model));
        assertFalse(MP_ROW_MODELS.contains(EXPOSURE_MODEL), "the 40 ordinary models retain their own inventory");
        TableName table = model.getDeclaredAnnotation(TableName.class);
        assertNotNull(table);
        assertEquals("public", table.schema());
        assertEquals("gateway_url_exposure_t0", table.value(),
                "SINGLE uses the exact physical name; ordinary logical routing is unchanged");
        assertTrue(table.autoResultMap());
        for (String ordinaryModel : MP_ROW_MODELS) {
            assertFalse(table.value().equals(Class.forName(ordinaryModel)
                    .getDeclaredAnnotation(TableName.class).value()), "the technical table has one row owner");
        }

        Class<?> mapper = Class.forName(EXPOSURE_MAPPER);
        assertTrue(mapper.isInterface());
        assertTrue(EgonColaMapper.class.isAssignableFrom(mapper));
        EgonColaExplicitTenantScopeMapper scope = mapper.getDeclaredAnnotation(
                EgonColaExplicitTenantScopeMapper.class);
        assertNotNull(scope);
        assertEquals(model, scope.technicalModel());
        assertEquals(EXPOSURE_TABLE, scope.technicalTable());
        InterceptorIgnore ignore = mapper.getDeclaredAnnotation(InterceptorIgnore.class);
        assertNotNull(ignore);
        assertEquals("true", ignore.tenantLine());
        for (Method attribute : InterceptorIgnore.class.getDeclaredMethods()) {
            if (!attribute.getName().equals("tenantLine")) {
                assertTrue(Objects.deepEquals(attribute.getDefaultValue(), attribute.invoke(ignore)),
                        "the technical mapper may bypass only tenantLine: " + attribute.getName());
            }
        }

        String text = Files.readString(EXPOSURE_XML);
        Matcher namespace = NAMESPACE.matcher(text);
        assertTrue(namespace.find());
        assertEquals(EXPOSURE_MAPPER, namespace.group(1));
        Map<String, String> sql = exposureStatements(text);
        assertEquals(Set.of("findOwned", "findOwnedRoute", "findRecoveryIds", "scanBinding", "lockPublicationDomain",
                "compareAndSetDesired", "compareAndSetContent", "bindRouteUid", "recordObservation"), sql.keySet());
        for (var statement : sql.entrySet()) {
            assertTrue(statement.getValue().contains(EXPOSURE_TABLE), statement.getKey());
            assertTrue(Pattern.compile("\\btenant_id\\s*=\\s*0\\b").matcher(statement.getValue()).find(),
                    statement.getKey() + " must constrain the technical tenant explicitly");
            assertTrue(statement.getValue().contains("deleted_at IS NULL"),
                    statement.getKey() + " must retain the active-row predicate");
            assertFalse(statement.getValue().contains("${"), "no substituted SQL in " + statement.getKey());
        }
        for (String id : List.of("findOwned", "findOwnedRoute", "findRecoveryIds")) {
            assertTrue(sql.get(id).contains("owner_tenant_id = #{owner}"), id + " requires the trusted owner");
        }
        String recovery = sql.get("findRecoveryIds");
        assertTrue(recovery.contains("id &gt; #{afterId}"));
        assertTrue(recovery.contains("ORDER BY id ASC LIMIT #{limit}"));
        assertTrue(recovery.contains("reconcile_status IN ('PENDING', 'SYNCED')"));
        for (String id : List.of("compareAndSetDesired", "compareAndSetContent", "bindRouteUid", "recordObservation")) {
            String where = sql.get(id).substring(sql.get(id).indexOf("WHERE"));
            assertTrue(where.contains("owner_tenant_id = #{record.ownerTenantId}"), id);
            assertTrue(where.contains("id = #{record.id}"), id);
            assertTrue(where.contains("version = #{record.version}"), id + " must keep its optimistic CAS");
        }
        // The only owner-free reads are the internal, fixed-binding overlap scan and transaction lock.
        String scan = sql.get("scanBinding");
        for (String predicate : List.of("cluster_id = #{cluster}", "gateway_namespace = #{gatewayNamespace}",
                "gateway_name = #{gatewayName}", "listener_name = #{listenerName}",
                "id &gt; #{afterId}", "ORDER BY id ASC LIMIT #{limit}")) {
            assertTrue(scan.contains(predicate), "cross-owner overlap scan must stay bounded by " + predicate);
        }
        assertFalse(scan.contains("owner_tenant_id ="), "overlap detection must include other owners");
        assertTrue(sql.get("lockPublicationDomain").contains("pg_advisory_xact_lock("));
        assertTrue(sql.get("lockPublicationDomain").contains("current_setting('transaction_isolation')"));
        assertTrue(sql.get("lockPublicationDomain").contains("'read committed'"));
        assertTrue(statements(text, "delete", "insert").isEmpty(), "reservations are never physically removed");

        for (String handler : typeHandlers(text)) {
            assertTrue(Files.isRegularFile(sourceOf(handler))
                            || handler.equals("com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler"),
                    "only the exact framework enum handler has no local source: " + handler);
            assertTrue(TypeHandler.class.isAssignableFrom(Class.forName(handler)), handler);
        }
        for (Path xml : mapperXmlFiles()) {
            Matcher candidate = NAMESPACE.matcher(Files.readString(xml));
            assertTrue(candidate.find(), xml.toString());
            EgonColaExplicitTenantScopeMapper marker = Class.forName(candidate.group(1))
                    .getDeclaredAnnotation(EgonColaExplicitTenantScopeMapper.class);
            if (marker != null && marker.technicalModel() != Void.class) {
                assertEquals(EXPOSURE_XML, xml, "no additional fixed-zero mapper may enter this inventory");
            }
        }
    }

    @Test
    void guardedDeleteStatementsReclaimSoftDeleteAndMutatingMethodsReportRowCounts()
            throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path xml : mapperXmlFiles()) {
            String text = Files.readString(xml);
            Matcher namespace = NAMESPACE.matcher(text);
            if (!namespace.find()) {
                continue;
            }
            if (text.contains("<delete") && !text.contains("SET deleted_at")
                    && !text.contains("deleted_at =")) {
                violations.add(xml + " hard-deletes instead of the guarded soft delete");
            }
            Class<?> accessInterface;
            try {
                accessInterface = Class.forName(namespace.group(1));
            } catch (ClassNotFoundException absent) {
                violations.add(namespace.group(1) + " is not loadable for " + xml);
                continue;
            }
            Map<String, Method> methods = new LinkedHashMap<>();
            for (Method method : accessInterface.getMethods()) {
                methods.put(method.getName(), method);
            }
            Matcher statement = MUTATING_STATEMENT.matcher(text);
            while (statement.find()) {
                Method method = methods.get(statement.group(2));
                if (method == null) {
                    violations.add(namespace.group(1) + "#" + statement.group(2)
                            + " is declared in XML but not on the interface");
                } else if (method.getReturnType() == void.class) {
                    violations.add(namespace.group(1) + "#" + statement.group(2)
                            + " must return the affected row count, not void");
                }
            }
        }
        assertTrue(violations.isEmpty(), "Guarded statement violations: " + violations);
    }

    @Test
    void migratedPersistenceNeverBypassesTheStarterGuards() throws IOException {
        List<String> bypasses = new ArrayList<>();
        List<String> jpaLeftovers = new ArrayList<>();
        for (String rowModel : MP_ROW_MODELS) {
            Path source = sourceOf(rowModel);
            if (!Files.isRegularFile(source)) {
                continue;
            }
            String text = Files.readString(source);
            if (text.contains("jakarta.persistence") || text.contains("javax.persistence")) {
                jpaLeftovers.add(source.toString());
            }
        }
        try (Stream<Path> paths = Files.walk(Path.of("src/main/java"))) {
            for (Path path : paths.filter(candidate -> candidate.toString()
                    .endsWith(".java")).toList()) {
                String text = Files.readString(path);
                for (String marker : List.of("new QueryWrapper", "new LambdaQueryWrapper",
                        "new UpdateWrapper", "new LambdaUpdateWrapper",
                        "com.baomidou.mybatisplus.extension.activerecord")) {
                    if (text.contains(marker)) {
                        bypasses.add(path + " uses " + marker);
                    }
                }
            }
        }
        assertTrue(jpaLeftovers.isEmpty(),
                "MP row models must not keep JPA annotations: " + jpaLeftovers);
        assertTrue(bypasses.isEmpty(),
                "Guarded access may not be bypassed by a wrapper: " + bypasses);
    }

    private static Class<?> getSuperclass(Class<?> type, List<String> violations,
            String rowModel) {
        Class<?> superclass = type.getSuperclass();
        assertNotNull(superclass, rowModel + " has no super type");
        assertFalse(Object.class.equals(superclass),
                rowModel + " must extend the starter model");
        return superclass;
    }

    private static String readRowModelSource(Class<?> type) {
        Path source = sourceOf(type.getName());
        try {
            if (!Files.isRegularFile(source)) {
                return "";
            }
            return Files.readString(source);
        } catch (IOException failure) {
            return fail("Cannot read row model source " + source, failure);
        }
    }

    private static Path sourceOf(String fullyQualified) {
        return Path.of("src/main/java").resolve(fullyQualified.replace('.', '/')
                + ".java");
    }

    private static List<String> statements(String text, String... tags) {
        List<String> bodies = new ArrayList<>();
        for (String tag : tags) {
            Matcher matcher = Pattern.compile("<" + tag + "\\b[^>]*>(.*?)</" + tag + ">",
                    Pattern.DOTALL).matcher(text);
            while (matcher.find()) {
                bodies.add(matcher.group(1));
            }
        }
        return bodies;
    }

    private static List<String> typeHandlers(String text) {
        List<String> handlers = new ArrayList<>();
        Matcher matcher = Pattern.compile(
                "typeHandler\\s*=\\s*\"([^\"]+)\"").matcher(text);
        while (matcher.find()) {
            handlers.add(matcher.group(1));
        }
        return handlers;
    }

    private static Map<String, String> exposureStatements(String text) {
        Map<String, String> fragments = new LinkedHashMap<>();
        Matcher fragment = Pattern.compile("<sql\\s+id=\"([^\"]+)\"[^>]*>(.*?)</sql>", Pattern.DOTALL).matcher(text);
        while (fragment.find()) {
            assertTrue(fragments.put(fragment.group(1), fragment.group(2)) == null,
                    "duplicate SQL fragment " + fragment.group(1));
        }
        Map<String, String> result = new LinkedHashMap<>();
        Matcher statement = Pattern.compile("<(select|update|insert|delete)\\s+id=\"([^\"]+)\"[^>]*>(.*?)</\\1>",
                Pattern.DOTALL).matcher(text);
        Pattern include = Pattern.compile("<include\\s+refid=\"([^\"]+)\"\\s*/>");
        while (statement.find()) {
            Matcher reference = include.matcher(statement.group(3));
            StringBuilder body = new StringBuilder();
            while (reference.find()) {
                String replacement = fragments.get(reference.group(1));
                assertNotNull(replacement, "unknown local SQL fragment " + reference.group(1));
                assertFalse(replacement.contains("<include"), "nested fragments require explicit contract review");
                reference.appendReplacement(body, Matcher.quoteReplacement(replacement));
            }
            reference.appendTail(body);
            assertTrue(result.put(statement.group(2), body.toString()) == null,
                    "duplicate exposure statement " + statement.group(2));
        }
        return result;
    }

    private static List<Path> mapperXmlFiles() throws IOException {
        if (!Files.isDirectory(MAPPER_XML_ROOT)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(MAPPER_XML_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".xml"))
                    .sorted()
                    .toList();
        }
    }

    static {
        // Static-initialisation guard: the Plan's table set is the contract, so
        // a duplicate entry here must break before any assertion runs.
        assertTrue(MP_ROW_MODELS.stream().map(name -> name.substring(
                        name.lastIndexOf('.') + 1)).distinct().count() == 40,
                "the declared row models must be 40 distinct types");
        assertTrue(MP_ROW_MODELS.stream().allMatch(name -> name.endsWith("PO")),
                "row models keep the PO suffix");
    }
}
