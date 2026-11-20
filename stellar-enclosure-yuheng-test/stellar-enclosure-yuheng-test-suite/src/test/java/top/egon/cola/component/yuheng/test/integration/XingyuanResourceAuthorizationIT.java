package top.egon.cola.component.yuheng.test.integration;

import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.fasterxml.jackson.databind.JsonNode;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler;
import top.egon.cola.component.yuheng.test.live.GatewayAdminTestClient;
import top.egon.cola.component.yuheng.test.tianquan.shoubing.UnifiedIdentityLiveClient;
import top.egon.cola.component.yuheng.test.tianquan.shoubing.UnifiedIdentityLiveClient.HttpAuditObservationVO;
import top.egon.cola.component.yuheng.test.tianquan.shoubing.UnifiedIdentityLiveClient.FormalReferenceDTO;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static top.egon.cola.component.yuheng.test.tianquan.shoubing.UnifiedIdentityLiveClient.boundedBody;
import static top.egon.cola.component.yuheng.test.tianquan.shoubing.UnifiedIdentityLiveClient.observedHttpCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Slf4j
class XingyuanResourceAuthorizationIT {

    record KnowledgeModelSelectionVO(String chatModel, String embeddingModel, String embeddingSpaceId, int dimensions) { }

    static KnowledgeModelSelectionVO knowledgePublishedModels(JsonNode models) {
        if (!models.isArray() || models.size() > 512) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        var chats = new ArrayList<JsonNode>(); var embeddings = new ArrayList<JsonNode>(); var keys = new java.util.HashSet<String>();
        for (var model : models) {
            if (!model.path("key").isTextual() || !model.path("key").asText().matches("[a-z0-9][a-z0-9-]{0,63}") || !keys.add(model.path("key").asText())
                    || !model.path("enabled").isBoolean()) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
            if (!model.path("enabled").booleanValue()) { continue; }
            if ("CHAT".equals(model.path("kind").asText())) { chats.add(model); }
            else if ("EMBEDDING".equals(model.path("kind").asText())) {
                if (!model.path("embeddingSpaceId").isTextual() || model.path("embeddingSpaceId").asText().isBlank() || model.path("embeddingSpaceId").asText().length() > 128
                        || !model.path("dimensions").isIntegralNumber() || !model.path("dimensions").canConvertToInt() || model.path("dimensions").intValue() < 1
                        || model.path("dimensions").intValue() > 16000) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
                embeddings.add(model);
            }
        }
        if (chats.isEmpty() || embeddings.isEmpty()) { throw new IllegalStateException("XINGYUAN_INPUT_UNAVAILABLE"); }
        chats.sort(java.util.Comparator.comparing(value -> value.path("key").asText())); embeddings.sort(java.util.Comparator.comparing(value -> value.path("key").asText()));
        var chat = chats.getFirst(); var embedding = embeddings.getFirst();
        return new KnowledgeModelSelectionVO(chat.path("key").asText(), embedding.path("key").asText(), embedding.path("embeddingSpaceId").asText(), embedding.path("dimensions").intValue());
    }

    static void requireManagedKnowledgeBase(JsonNode value, JsonNode command, KnowledgeModelSelectionVO models) {
        responseId(value);
        if (!command.path("name").equals(value.path("name")) || !command.path("description").equals(value.path("description"))
                || !"LOCAL_ONLY".equals(value.path("egressPolicy").asText()) || !"OWNER".equals(value.path("myRole").asText())
                || !models.chatModel().equals(value.path("chatModel").asText()) || !models.embeddingModel().equals(value.path("embeddingModel").asText())
                || !models.embeddingSpaceId().equals(value.path("embeddingSpaceId").asText()) || transactionProofLong(value, "dimensions") != models.dimensions()
                || !value.path("ownerActorId").isTextual() || value.path("ownerActorId").asText().isBlank() || value.path("ownerActorId").asText().length() > 128
                || transactionProofLong(value, "revision") != 1) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
    }

    @Test
    void knowledgeMetadataNeedsActuallyPublishedEnabledModelsAndExactOwnerProjection() {
        var mapper = UnifiedIdentityLiveClient.strictJson(); var models = mapper.createArrayNode();
        models.addObject().put("key", "chat-existing").put("enabled", true).put("kind", "CHAT");
        models.addObject().put("key", "embedding-existing").put("enabled", true).put("kind", "EMBEDDING").put("embeddingSpaceId", "space-existing").put("dimensions", 1536);
        var selected = knowledgePublishedModels(models); assertThat(selected.chatModel()).isEqualTo("chat-existing");
        var disabled = models.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) disabled.get(1)).put("enabled", false);
        assertThatThrownBy(() -> knowledgePublishedModels(disabled)).hasMessage("XINGYUAN_INPUT_UNAVAILABLE");
        var invalid = models.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.get(1)).put("dimensions", 0);
        assertThatThrownBy(() -> knowledgePublishedModels(invalid)).hasMessage("XINGYUAN_TARGET_UNSAFE");
        var duplicate = models.deepCopy().add(models.get(0)); assertThatThrownBy(() -> knowledgePublishedModels(duplicate)).hasMessage("XINGYUAN_TARGET_UNSAFE");
        var command = mapper.createObjectNode().put("name", "Task metadata").put("description", "No inference");
        var view = command.deepCopy().put("id", "123").put("ownerActorId", "actual-actor").put("myRole", "OWNER").put("egressPolicy", "LOCAL_ONLY")
                .put("chatModel", selected.chatModel()).put("embeddingModel", selected.embeddingModel()).put("embeddingSpaceId", selected.embeddingSpaceId()).put("dimensions", selected.dimensions()).put("revision", 1);
        assertThatCode(() -> requireManagedKnowledgeBase(view, command, selected)).doesNotThrowAnyException();
        assertThatThrownBy(() -> requireManagedKnowledgeBase(view.deepCopy().put("chatModel", "unpublished"), command, selected)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        assertThatThrownBy(() -> requireManagedKnowledgeBase(view.deepCopy().put("myRole", "READER"), command, selected)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "XINGYUAN_ISOLATED_INTEGRATION", matches = "true")
    void actualYuhengKnowledgeMetadataKeepsScopeCapabilityAndOriginalMembershipWithoutInference() throws Exception {
        var client = UnifiedIdentityLiveClient.isolated(); var mapper = UnifiedIdentityLiveClient.strictJson();
        Path manifestFile = Path.of(System.getProperty("xingyuan.isolated.manifest-file", System.getenv("XINGYUAN_INTEGRATION_MANIFEST")));
        byte[] bytes = UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024); var manifest = mapper.readTree(bytes);
        String digest = UnifiedIdentityLiveClient.sha256(bytes); Path root = Path.of(manifest.path("runtimeRoot").asText());
        Path evidence = Path.of(System.getProperty("xingyuan.isolated.evidence-directory", root.resolve("evidence").toString())); requireEvidenceDirectory(root, evidence);
        Path output = evidence.resolve("resource-knowledge-management.json"), intent = evidence.resolve("resource-knowledge-running.json");
        if (Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { throw new IllegalStateException("XINGYUAN_STATE_CONFLICT"); }
        if (Files.exists(intent, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { throw new IllegalStateException("XINGYUAN_COMMIT_UNKNOWN"); }
        requireRunningProcesses(root, manifestFile, manifest, digest, evidence, java.util.Set.of("yuheng"));
        String resource = client.resourceForAlias("edge-yuheng"), base = "/api/v1/yuheng/admin/knowledge-bases", page = base + "?page=1&size=100";
        var a = client.formal("default", resource, "admin"); var b = client.formal("tenant-b", resource, "admin"); client.verifiedClaims(a); client.verifiedClaims(b);
        Duration timeout = Duration.ofSeconds(manifest.path("deadlines").path("httpSeconds").intValue());
        var proof = mapper.createObjectNode().put("schemaVersion", 1).put("runId", manifest.path("runId").asText()).put("manifestSha256", digest)
                .put("scope", "YUHENG_ACTUAL_KNOWLEDGE_METADATA_SCOPE_AND_MEMBERSHIP_ONLY"); var observations = proof.putArray("observations");
        var created = new java.util.LinkedHashMap<String, JsonNode>();
        try (var http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var api = new NativeResourceHttpProbe("yuheng", root, manifestFile, digest, http, timeout);
            if (!a.getScopes().containsAll(java.util.Set.of("yuheng:knowledge:read", "yuheng:knowledge:write", "yuheng:llm:read"))
                    || !b.getScopes().containsAll(java.util.Set.of("yuheng:knowledge:read", "yuheng:knowledge:write"))) { throw new IllegalStateException("XINGYUAN_INPUT_UNAVAILABLE"); }
            var models = knowledgePublishedModels(api.request("GET", "/api/v1/yuheng/admin/llm/models", a, null, null, 200));
            var commands = new java.util.LinkedHashMap<String, JsonNode>();
            for (String realm : List.of("default", "tenant-b")) {
                var reference = realm.equals("default") ? a : b; var viewer = client.formal(realm, resource, "viewer"); client.verifiedClaims(viewer);
                var scopeOnly = acquireIdentityOnlyProbe(client, root, evidence, manifestFile, manifest, digest, realm, resource);
                var before = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", realm, "KNOWLEDGE_COUNTS", null);
                var baseline = api.request("GET", page, reference, null, null, 200);
                if (!baseline.path("items").isArray() || transactionProofLong(baseline, "page") != 1 || transactionProofLong(baseline, "size") != 100) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                var success = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", realm, "KNOWLEDGE_COUNTS", null);
                UnifiedIdentityLiveClient.requireOnlyBusinessMethodDelta(before, success, "listKnowledgeBases", 1);
                var command = mapper.createObjectNode().put("name", "it-" + manifest.path("runId").asText() + '-' + realm + '-' + UUID.randomUUID().toString().substring(0, 8))
                        .put("description", "Task metadata only").put("egressPolicy", "LOCAL_ONLY").put("chatModel", models.chatModel()).put("embeddingModel", models.embeddingModel());
                commands.put(realm, command);
                api.request("GET", page, null, null, null, 401); api.request("GET", page, scopeOnly, null, null, 403); api.request("GET", page, viewer, null, null, 403);
                api.request("POST", base, scopeOnly, command, null, 403); api.request("POST", base, viewer, command, null, 403); api.request("POST", base, null, command, null, 401);
                var denied = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", realm, "KNOWLEDGE_COUNTS", null);
                if (!denied.equals(success)) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                var recovery = api.request("GET", page, reference, null, null, 200); if (!recovery.equals(baseline)) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                var recovered = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", realm, "KNOWLEDGE_COUNTS", null);
                UnifiedIdentityLiveClient.requireOnlyBusinessMethodDelta(denied, recovered, "listKnowledgeBases", 1);
                var item = observations.addObject().put("realmCode", realm).put("tokenReference", reference.getTokenSha256()).put("viewerReference", viewer.getTokenSha256())
                        .put("identityOnlyTokenReference", scopeOnly.getTokenSha256()).put("zeroMethodsDuringAuthorizationDenial", true)
                        .put("baselineMetadataSha256", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(baseline)));
                item.set("before", before); item.set("successful", success); item.set("denied", denied); item.set("recovered", recovered);
            }
            XingyuanNativeAuthenticationIT.publishNewNativeEvidence(root, intent, mapper.createObjectNode().put("schemaVersion", 1).put("runId", manifest.path("runId").asText()).put("manifestSha256", digest)
                    .put("status", "RUNNING").put("commandSha256", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(commands))).put("recordedAt", Instant.now().toString()));
            for (String realm : List.of("default", "tenant-b")) {
                var reference = realm.equals("default") ? a : b; var command = commands.get(realm);
                var before = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", realm, "KNOWLEDGE_COUNTS", null);
                var value = api.request("POST", base, reference, command, null, 201); requireManagedKnowledgeBase(value, command, models); created.put(realm, value);
                var after = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", realm, "KNOWLEDGE_COUNTS", null);
                UnifiedIdentityLiveClient.requireOnlyBusinessMethodDelta(before, after, "createKnowledgeBase", 1);
                String path = base + '/' + responseId(value);
                if (!value.equals(api.request("GET", path, reference, null, null, 200))
                        || !value.equals(api.request("GET", path, reference, null, (realm.equals("default") ? b : a).getTenantId(), 200))) {
                    throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
                }
                var item = observations.addObject().put("realmCode", realm).put("knowledgeBaseId", Long.toString(responseId(value)))
                        .put("metadataSha256", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(value)))
                        .put("ownerReference", UnifiedIdentityLiveClient.sha256(value.path("ownerActorId").asText().getBytes(StandardCharsets.UTF_8))).put("createStatus", 201);
                item.set("before", before); item.set("after", after);
            }
            boolean sameActor = created.get("default").path("ownerActorId").equals(created.get("tenant-b").path("ownerActorId"));
            proof.put("sameInstallationActorAcrossIssuers", sameActor);
            for (String realm : List.of("default", "tenant-b")) {
                var reference = realm.equals("default") ? a : b; var foreign = created.get(realm.equals("default") ? "tenant-b" : "default");
                var before = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", realm, "KNOWLEDGE_COUNTS", null);
                var response = api.request("GET", base + '/' + responseId(foreign), reference, null, null, sameActor ? 200 : 403);
                if (sameActor ? !response.equals(foreign) : !"KNOWLEDGE_FORBIDDEN".equals(response.path("code").asText())) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                var after = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", realm, "KNOWLEDGE_COUNTS", null);
                UnifiedIdentityLiveClient.requireOnlyBusinessMethodDelta(before, after, "getKnowledgeBase", 1);
                var own = created.get(realm); if (!own.equals(api.request("GET", base + '/' + responseId(own), reference, null, null, 200))) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
            }
        }
        requireRunningProcesses(root, manifestFile, manifest, digest, evidence, java.util.Set.of("yuheng"));
        proof.put("status", "PASSED").put("externalInferenceExecuted", false).put("recordedAt", Instant.now().toString());
        XingyuanNativeAuthenticationIT.publishNewNativeEvidence(root, output, proof);
    }

    private static JsonNode requireFullPhase(Path root, Path evidence, JsonNode manifest, String digest, String filename, String status) throws Exception {
        return requireFullPhase(root, evidence, manifest, digest, filename, status, null);
    }

    private static JsonNode requireFullPhase(Path root, Path evidence, JsonNode manifest, String digest, String filename,
            String status, String expectedSha256) throws Exception {
        requireEvidenceDirectory(root, evidence); Path relative = Path.of(filename);
        if (relative.isAbsolute() || !relative.normalize().equals(relative) || relative.startsWith("..")) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        var mapper = UnifiedIdentityLiveClient.strictJson();
        byte[] proofBytes = UnifiedIdentityLiveClient.protectedBytes(evidence.resolve(relative), evidence, 128 * 1024);
        if (expectedSha256 != null && !expectedSha256.equals(UnifiedIdentityLiveClient.sha256(proofBytes))) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
        var value = mapper.readTree(proofBytes);
        var intent = mapper.readTree(UnifiedIdentityLiveClient.protectedBytes(evidence.resolve("intent.json"), evidence, 128 * 1024));
        if (!value.path("schemaVersion").isInt() || value.path("schemaVersion").intValue() != 1 || !manifest.path("runId").asText().equals(value.path("runId").asText())
                || !digest.equals(value.path("manifestSha256").asText()) || !status.equals(value.path("status").asText())
                || !manifest.path("runId").equals(intent.path("runId")) || !digest.equals(intent.path("manifestSha256").asText()) || !"full".equals(intent.path("caseSet").asText())) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        String time = value.has("completedAt") ? "completedAt" : value.has("recordedAt") ? "recordedAt" : value.has("observedAt") ? "observedAt" : "executedAt";
        Instant observed = transactionProofInstant(value, time);
        if (observed.isBefore(transactionProofInstant(intent, "startedAt")) || observed.isAfter(Instant.now())) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        return value;
    }

    @Test
    void fullResourceProofsCannotReuseAnotherAttemptStatusOrMutablePrivateFile(@org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath(); Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Path base = Files.createDirectory(root.resolve("evidence"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path evidence = Files.createDirectory(base.resolve("verify-" + UUID.randomUUID()), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var mapper = UnifiedIdentityLiveClient.strictJson(); String runId = UUID.randomUUID().toString(), digest = "a".repeat(64); Instant now = Instant.now();
        var manifest = mapper.createObjectNode().put("runId", runId); var intent = mapper.createObjectNode().put("runId", runId).put("manifestSha256", digest).put("caseSet", "full").put("startedAt", now.minusSeconds(2).toString());
        var proof = mapper.createObjectNode().put("schemaVersion", 1).put("runId", runId).put("manifestSha256", digest).put("status", "PASSED").put("recordedAt", now.toString());
        XingyuanNativeAuthenticationIT.publishNewNativeEvidence(root, evidence.resolve("intent.json"), intent);
        Path path = evidence.resolve("controlled.json"); XingyuanNativeAuthenticationIT.publishNewNativeEvidence(root, path, proof);
        String proofSha256 = UnifiedIdentityLiveClient.sha256(Files.readAllBytes(path));
        assertThatCode(() -> requireFullPhase(root, evidence, manifest, digest, "controlled.json", "PASSED", proofSha256)).doesNotThrowAnyException();
        assertThatThrownBy(() -> requireFullPhase(root, evidence, manifest, digest, "controlled.json", "PASSED", "0".repeat(64)))
                .hasMessage("XINGYUAN_VERIFICATION_FAILED");
        for (String fault : List.of("foreignRun", "foreignDigest", "oldAttempt", "future", "skipped", "schemaBoolean")) {
            var wrong = proof.deepCopy();
            if (fault.equals("foreignRun")) { wrong.put("runId", UUID.randomUUID().toString()); }
            else if (fault.equals("foreignDigest")) { wrong.put("manifestSha256", "b".repeat(64)); }
            else if (fault.equals("oldAttempt")) { wrong.put("recordedAt", now.minusSeconds(3).toString()); }
            else if (fault.equals("future")) { wrong.put("recordedAt", now.plusSeconds(3600).toString()); }
            else if (fault.equals("skipped")) { wrong.put("status", "SKIPPED"); }
            else { wrong.put("schemaVersion", true); }
            Files.write(path, mapper.writeValueAsBytes(wrong));
            assertThatThrownBy(() -> requireFullPhase(root, evidence, manifest, digest, "controlled.json", "PASSED")).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        }
        Files.write(path, mapper.writeValueAsBytes(proof)); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> requireFullPhase(root, evidence, manifest, digest, "controlled.json", "PASSED")).hasMessage("XINGYUAN_TARGET_UNSAFE");
        assertThatThrownBy(() -> requireFullPhase(root, evidence, manifest, digest, "../controlled.json", "PASSED")).hasMessage("XINGYUAN_TARGET_UNSAFE");
    }

    private static final String CILIUM_BROWSER_PROOF = "browser-cilium-direct.json";
    private static final Set<String> CILIUM_BROWSER_CASES = Set.of(
            "PORTAL_SIGN_IN", "STANDALONE_SIGN_IN", "DIRECT_REST", "SSE_RESUME", "ACCESS_REAUTHORIZE", "WRONG_AUDIENCE",
            "WRONG_TENANT", "TENANT_SWITCH", "LOCAL_LOGOUT", "GLOBAL_LOGOUT", "BROWSER_CREDENTIAL_BOUNDARY");

    private static List<String> requireCiliumBrowserPhases(Path root, Path evidence, JsonNode manifest, String digest) throws Exception {
        var proof = requireFullPhase(root, evidence, manifest, digest, CILIUM_BROWSER_PROOF, "PASSED");
        var files = new ArrayList<String>();
        files.add(CILIUM_BROWSER_PROOF);
        for (var entry : browserEvidenceEntries(proof)) {
            String name = entry.path("path").textValue();
            var caseProof = requireFullPhase(root, evidence, manifest, digest, name, "PASSED", entry.path("sha256").textValue());
            if (!entry.path("caseId").equals(caseProof.path("caseId"))
                    || !"DIRECT_CILIUM_BROWSER".equals(caseProof.path("scope").textValue())) {
                throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
            }
            files.add(name);
        }
        return List.copyOf(files);
    }

    static List<JsonNode> browserEvidenceEntries(JsonNode proof) {
        var cases = proof.path("cases");
        if (!"DIRECT_CILIUM_BROWSER".equals(proof.path("scope").textValue())
                || !cases.isArray() || cases.size() != CILIUM_BROWSER_CASES.size()) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
        var result = new ArrayList<JsonNode>();
        var seen = new HashSet<String>();
        for (JsonNode entry : cases) {
            String caseId = entry.path("caseId").asText();
            String name = "browser-cilium-" + caseId.toLowerCase(java.util.Locale.ROOT).replace('_', '-') + ".json";
            if (!entry.isObject() || entry.size() != 3 || !CILIUM_BROWSER_CASES.contains(caseId) || !seen.add(caseId)
                    || !name.equals(entry.path("path").textValue()) || !entry.path("sha256").asText().matches("[0-9a-f]{64}")) {
                throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
            }
            result.add(entry);
        }
        return List.copyOf(result);
    }

    @Test
    void directBrowserReceiptRejectsLegacyAndMissingOrDuplicateCases() {
        var mapper = UnifiedIdentityLiveClient.strictJson();
        var proof = mapper.createObjectNode().put("scope", "DIRECT_CILIUM_BROWSER");
        var cases = proof.putArray("cases");
        for (String id : CILIUM_BROWSER_CASES.stream().sorted().toList()) {
            cases.addObject().put("caseId", id).put("path", "browser-cilium-" + id.toLowerCase(java.util.Locale.ROOT).replace('_', '-') + ".json")
                    .put("sha256", "a".repeat(64));
        }
        assertThat(browserEvidenceEntries(proof)).hasSize(11);
        var older = proof.deepCopy().put("scope", "EDGE_BINDING");
        assertThatThrownBy(() -> browserEvidenceEntries(older)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        var incomplete = proof.deepCopy();
        incomplete.withArray("cases").remove(0);
        assertThatThrownBy(() -> browserEvidenceEntries(incomplete)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        var duplicate = proof.deepCopy();
        duplicate.withArray("cases").set(0, cases.get(1));
        assertThatThrownBy(() -> browserEvidenceEntries(duplicate)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        var foreign = proof.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) foreign.path("cases").get(0)).put("path", "../browser-cilium.json");
        assertThatThrownBy(() -> browserEvidenceEntries(foreign)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "XINGYUAN_ISOLATED_INTEGRATION", matches = "true")
    void verifyResourceAuthorization() throws Exception {
        var mapper = UnifiedIdentityLiveClient.strictJson();
        Path manifestFile = Path.of(System.getProperty("xingyuan.isolated.manifest-file", System.getenv("XINGYUAN_INTEGRATION_MANIFEST")));
        byte[] raw = UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024); var manifest = mapper.readTree(raw);
        String digest = UnifiedIdentityLiveClient.sha256(raw); Path root = Path.of(manifest.path("runtimeRoot").asText());
        Path evidence = Path.of(System.getProperty("xingyuan.isolated.evidence-directory")); requireEvidenceDirectory(root, evidence);
        if (evidence.equals(root.resolve("evidence"))) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        var intent = mapper.readTree(UnifiedIdentityLiveClient.protectedBytes(evidence.resolve("intent.json"), evidence, 128 * 1024));
        if (!manifest.path("runId").asText().equals(intent.path("runId").asText()) || !digest.equals(intent.path("manifestSha256").asText())
                || !"full".equals(intent.path("caseSet").asText())) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        nativeUserHttpTargetsCannotChooseAnUnreviewedHostOrMutation(); nativeSqlTargetsRejectForeignReceiptsAndIdentifiersBeforeConnecting();
        Path guards = evidence.resolve("resource-live-guards.json");
        var guard = new ProcessBuilder("python3", "-I", Path.of(manifest.path("repoRoot").asText()).resolve("scripts/unified-xingyuan/lib/isolated_integration.py").toString(),
                "observe-live-guards", manifestFile.toString(), guards.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!guard.waitFor(manifest.path("deadlines").path("operationSeconds").intValue(), TimeUnit.SECONDS) || guard.exitValue() != 0) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        } finally { if (guard.isAlive()) { guard.destroy(); guard.waitFor(10, TimeUnit.SECONDS); } }
        var owned = requireFullPhase(root, evidence, manifest, digest, guards.getFileName().toString(), "PASSED");
        if (!"OWNED_RUNNING_COHORT_AND_ORIGINAL_FACILITY_GUARDS_ONLY".equals(owned.path("scope").asText()) || transactionProofLong(owned, "ownedDatabaseCount") != 9 || owned.path("proofs").size() != 3) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
        XingyuanNativeAuthenticationIT.publishLiveCase(root, evidence, manifest, digest, "LIVE-001", "resource", "verifyResourceAuthorization", List.of("resource-live-guards.json"), false);
        var transactions = readJpaMpTransactionProofs(root, evidence, manifest, digest);
        var transactionFiles = new ArrayList<>(transactions.stream().map(path -> evidence.relativize(path).toString()).toList()); transactionFiles.add("junit-outbox.json");
        XingyuanNativeAuthenticationIT.publishLiveCase(root, evidence, manifest, digest, "LIVE-002", "resource", "verifyResourceAuthorization", transactionFiles, false);
        threeIssuerMetadataUsesDistinctPublicKeysAndRetiresTheRoot();
        XingyuanNativeAuthenticationIT.publishLiveCase(root, evidence, manifest, digest, "LIVE-003", "resource", "verifyResourceAuthorization", List.of("resource-issuer-discovery.json"), false);
        var browser = requireCiliumBrowserPhases(root, evidence, manifest, digest);
        for (String id : List.of("LIVE-004", "LIVE-005", "LIVE-008", "LIVE-018", "LIVE-019", "LIVE-020")) {
            XingyuanNativeAuthenticationIT.publishLiveCase(root, evidence, manifest, digest, id, "resource", "verifyResourceAuthorization", browser, false);
        }
        var formal = requireFullPhase(root, evidence, manifest, digest, "formal-inputs.json", "COMMITTED");
        if (!"CLI-006".equals(formal.path("operationId").asText()) || transactionProofLong(formal, "inputCount") < 1
                || formal.path("inputs").size() != transactionProofLong(formal, "inputCount")) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        UnifiedIdentityLiveClient.isolated();
        XingyuanNativeAuthenticationIT.publishLiveCase(root, evidence, manifest, digest, "LIVE-010", "resource", "verifyResourceAuthorization", List.of("formal-inputs.json"), false);
        for (String name : List.of("native-resource-grant.json", "native-field-write.json", "native-tenant-isolation.json")) { requireFullPhase(root, evidence, manifest, digest, name, "PASSED"); }
        var grant = requireFullPhase(root, evidence, manifest, digest, "native-resource-grant.json", "PASSED");
        if (!"ACTUAL_RESOURCE_GRANT_REMOVAL_AND_RESTORE_ONLY".equals(grant.path("scope").asText()) || grant.path("proofs").size() != 3
                || transactionProofLong(grant.path("proofs").get(1), "status") != 403 || transactionProofLong(grant.path("proofs").get(2), "status") != 200) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        platformHostsRejectTamperingAndForeignAudienceBeforeTheirOriginalBusiness();
        actualCiliumReadRequiresTheOriginalApplicationAuthorization();
        actualMcpResourceReadEnforcesPublishedScopeAndExactFinePermission();
        var platforms = requireFullPhase(root, evidence, manifest, digest, "resource-platform-http-boundaries.json", "PASSED");
        if (platforms.path("observations").size() != 8) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        XingyuanNativeAuthenticationIT.publishLiveCase(root, evidence, manifest, digest, "LIVE-006", "resource", "verifyResourceAuthorization",
                List.of("native-resource-grant.json", "native-resource-grant-result-01.json", "native-resource-grant-result-02.json", "resource-platform-http-boundaries.json", "resource-mcp-boundaries.json", "mcp-authorization.json", "mcp-authorization-execution.json"), false);
        var tenancy = new ArrayList<>(browser); tenancy.add("native-tenant-isolation.json"); tenancy.add("resource-platform-http-boundaries.json");
        XingyuanNativeAuthenticationIT.publishLiveCase(root, evidence, manifest, digest, "LIVE-007", "resource", "verifyResourceAuthorization", tenancy, false);
        actualYuhengSharedStateIoUnavailabilityAndFixedMetricsRecover();
        XingyuanNativeAuthenticationIT.publishLiveCase(root, evidence, manifest, digest, "LIVE-009", "resource", "verifyResourceAuthorization",
                List.of("resource-platform-http-boundaries.json", "resource-shared-state-metrics.json", "native-light-graphql.json", "native-web-graphql.json"), false);
        XingyuanNativeAuthenticationIT.publishLiveCase(root, evidence, manifest, digest, "LIVE-016", "resource", "verifyResourceAuthorization", List.of("native-field-write.json", "junit-field-owner.json", "junit-field-consumer.json"), false);
        actualLlmProtocolsRejectMissingAudienceAndScopeBeforeInference(); actualYuhengKnowledgeMetadataKeepsScopeCapabilityAndOriginalMembershipWithoutInference();
        XingyuanNativeAuthenticationIT.publishLiveCase(root, evidence, manifest, digest, "LIVE-017", "resource", "verifyResourceAuthorization",
                List.of("resource-llm-protocol-guards.json", "resource-knowledge-management.json"), false);
        nativeUserInfoRequestsUseRealRealmTokensAndReturnAuditCorrelation(); formalPublicFamilyRevocationRejectsTheSameUnexpiredTokenBeforeBusiness();
        for (String protocol : List.of("platform-http", "native-userinfo", "native-rpc", "mcp")) {
            var audit = requireFullPhase(root, evidence, manifest, digest, "protocol-" + protocol + "-audits.json", "PASSED");
            if (transactionProofLong(audit, "storedEventCount") != transactionProofLong(audit, "spoolAbsentAfterReceiptCount")) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        }
        XingyuanNativeAuthenticationIT.publishLiveCase(root, evidence, manifest, digest, "LIVE-021", "resource", "verifyResourceAuthorization",
                List.of("resource-native-userinfo.json", "resource-platform-http-boundaries.json", "native-rpc-contract-probe.json", "resource-mcp-boundaries.json", "protocol-platform-http-audits.json", "protocol-native-userinfo-audits.json", "protocol-native-rpc-audits.json", "protocol-mcp-audits.json", "resource-formal-family-revocation.json"), false);
        actualYuhengSpoolFaultsFailClosedAndRecoverWithoutEnteringBusiness();
        XingyuanNativeAuthenticationIT.publishLiveCase(root, evidence, manifest, digest, "LIVE-022", "resource", "verifyResourceAuthorization", List.of("resource-spool-readiness.json", "resource-shared-state-metrics.json"), false);
        var cilium = requireFullPhase(root, evidence, manifest, digest, CILIUM_AUTHORIZATION_PROOF, "PASSED");
        if (cilium.path("identities").size() != 2) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        XingyuanNativeAuthenticationIT.publishLiveCase(root, evidence, manifest, digest, "LIVE-024", "resource", "verifyResourceAuthorization",
                List.of("resource-platform-http-boundaries.json", CILIUM_AUTHORIZATION_PROOF, "resource-mcp-boundaries.json", "mcp-publication.json", "mcp-authorization.json", "mcp-authorization-execution.json", "native-resource-grant.json", "resource-llm-protocol-guards.json", "resource-knowledge-management.json"), false);
        actualOwnedStopRetainsTheOriginalCohortAndBoundedPreservationEvidence();
        XingyuanNativeAuthenticationIT.publishLiveCase(root, evidence, manifest, digest, "LIVE-023", "resource", "verifyResourceAuthorization", List.of("resource-owned-stop-preservation.json"), false);
    }

    @Test
    void ciliumExposureEvidenceRequiresTheExactUrlAndCurrentObserverConditions() {
        var mapper = UnifiedIdentityLiveClient.strictJson();
        URI url = URI.create("https://public.apps.example.test/api/v1/yuheng/admin/scopes");
        var receipt = mapper.createObjectNode().put("exposureId", "912").put("url", url.toASCIIString()).put("method", "GET")
                .put("desiredState", "OPEN").put("observedState", "OPEN").put("reconcileStatus", "SYNCED")
                .put("desiredGeneration", "8").put("observedGeneration", "8");
        receipt.putObject("kubernetesRoute").put("namespace", "yuheng-routes").put("name", "yuheng-exposure-912")
                .put("kind", "HTTPRoute").put("uid", "route-uid").put("attached", true)
                .put("controllerName", "io.cilium/gateway-controller")
                .put("metadataGeneration", "12").put("conditionObservedGeneration", "12");
        for (String condition : List.of("Accepted", "ResolvedRefs", "Programmed", "DataplaneConverged")) {
            receipt.withArray("conditions").addObject().put("type", condition).put("status", "True").put("observedGeneration", "8");
        }
        assertThatCode(() -> requireCurrentCiliumExposure(receipt, "912", url)).doesNotThrowAnyException();
        for (var changed : List.of(receipt.deepCopy().put("observedGeneration", "7"), receipt.deepCopy().put("reconcileStatus", "PENDING"),
                receipt.deepCopy().put("url", "https://foreign.example.test/api/v1/yuheng/admin/scopes"))) {
            assertThatThrownBy(() -> requireCurrentCiliumExposure(changed, "912", url)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        }
        var parent = receipt.deepCopy();
        parent.withObject("kubernetesRoute").put("conditionObservedGeneration", "11");
        assertThatThrownBy(() -> requireCurrentCiliumExposure(parent, "912", url)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        var duplicate = receipt.deepCopy();
        duplicate.withArray("conditions").add(duplicate.path("conditions").get(0));
        assertThatThrownBy(() -> requireCurrentCiliumExposure(duplicate, "912", url)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
    }

    static String mcpTaskServerCode(String runId) {
        if (runId == null || !runId.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        return "xingyuan-it-" + runId;
    }

    private record CiliumMcpFixture(URI endpoint, String serverCode, String publicationId, String catalogSha256,
            String bindingsSha256, String resourceKey, String foreignResourceKey) {
        static CiliumMcpFixture from(JsonNode manifest) {
            String server = mcpTaskServerCode(manifest.path("runId").textValue());
            var entry = manifest.path("ciliumMcp");
            var publication = entry.path("publicationId").textValue();
            var catalog = entry.path("catalogSha256").textValue();
            var bindings = entry.path("bindingsSha256").textValue();
            if (!positiveDecimal(publication) || catalog == null || !catalog.matches("[0-9a-f]{64}")
                    || bindings == null || !bindings.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
            }
            return new CiliumMcpFixture(reviewedHttps(entry.path("endpoint").asText(), "/mcp/" + server), server,
                    publication, catalog, bindings, XingyuanResourceAuthorizationIT.resourceKey(entry, "resourceKey"),
                    XingyuanResourceAuthorizationIT.resourceKey(entry, "foreignResourceKey"));
        }

        void requirePublication(JsonNode proof) {
            if (!"TIANSHU_SI_AND_YUHENG_MCP_MAPPING".equals(proof.path("scope").textValue())
                    || !serverCode.equals(proof.path("serverCode").textValue())
                    || !endpoint.toASCIIString().equals(proof.path("endpoint").textValue())
                    || !publicationId.equals(proof.path("publicationId").textValue())
                    || !catalogSha256.equals(proof.path("catalogSha256").textValue())
                    || !bindingsSha256.equals(proof.path("bindingsSha256").textValue())) {
                throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
            }
        }
    }

    @Test
    void mcpFixtureRequiresItsMountedCatalogueAndApprovedCiliumEndpoint() {
        var manifest = UnifiedIdentityLiveClient.strictJson().createObjectNode().put("runId", "01234567-89ab-cdef-0123-456789abcdef");
        String server = mcpTaskServerCode(manifest.path("runId").textValue());
        var target = manifest.putObject("ciliumMcp").put("endpoint", "https://public.apps.example.test/mcp/" + server)
                .put("publicationId", "904").put("catalogSha256", "a".repeat(64)).put("bindingsSha256", "b".repeat(64))
                .put("resourceKey", "mcp-access").put("foreignResourceKey", "tianshu-admin");
        var fixture = CiliumMcpFixture.from(manifest);
        var proof = target.deepCopy().put("scope", "TIANSHU_SI_AND_YUHENG_MCP_MAPPING").put("serverCode", server);
        assertThatCode(() -> fixture.requirePublication(proof)).doesNotThrowAnyException();
        assertThatThrownBy(() -> fixture.requirePublication(proof.deepCopy().put("catalogSha256", "c".repeat(64))))
                .hasMessage("XINGYUAN_VERIFICATION_FAILED");
        assertThatThrownBy(() -> fixture.requirePublication(proof.deepCopy().put("publicationId", "903")))
                .hasMessage("XINGYUAN_VERIFICATION_FAILED");
        target.put("endpoint", "https://127.0.0.1:28185/mcp/" + server);
        assertThatThrownBy(() -> CiliumMcpFixture.from(manifest)).hasMessage("XINGYUAN_TARGET_UNSAFE");
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreType
    record McpHttpExchangeVO(HttpAuditObservationVO observation, String sessionId, String body) {
        @Override public String toString() { return "McpHttpExchangeVO[status=" + observation.status() + ",redacted]"; }
    }

    private static McpHttpExchangeVO mcpDataRequest(HttpClient http, Duration timeout, Path root, CiliumMcpFixture fixture, FormalReferenceDTO reference,
            String sessionId, String method, String tenantHeader, int expected) throws Exception {
        if (!java.util.Set.of("initialize", "resources/read").contains(method) || method.equals("resources/read") && (sessionId == null || sessionId.isBlank())) {
            throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
        }
        String code = fixture.serverCode(), trace = UUID.randomUUID().toString().replace("-", "");
        var mapper = UnifiedIdentityLiveClient.strictJson(); var body = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", method);
        var params = body.putObject("params");
        if (method.equals("initialize")) { params.put("protocolVersion", "2025-11-25"); }
        else { params.put("uri", "egon://" + code + "/scopes"); }
        var request = HttpRequest.newBuilder(fixture.endpoint()).timeout(timeout).header("content-type", "application/json")
                .header("Mcp-Protocol-Version", "2025-11-25").header("traceparent", "00-" + trace + '-' + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "-01");
        if (sessionId != null) { request.header("Mcp-Session-Id", sessionId); }
        if (tenantHeader != null) { request.header("X-Tenant-Id", tenantHeader); }
        if (reference != null) {
            request.header("Authorization", "Bearer " + fixtureBearer(root, reference));
        }
        Instant started = Instant.now(); var captured = new ByteArrayOutputStream();
        var response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), boundedBody(captured));
        Instant completed = Instant.now();
        if (response.statusCode() != expected || expected != 200 && !response.headers().firstValue("cache-control").orElse("").equals("no-store")) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
        var content = mapper.readTree(captured.toByteArray());
        if (expected == 200 && (content.hasNonNull("error") || !content.path("result").isObject())
                || expected != 200 && !content.hasNonNull("error")) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        // MCP uses the supplied traceparent directly; the signed owner observation proves this correlation.
        return new McpHttpExchangeVO(new HttpAuditObservationVO(trace, expected, started, completed), response.headers().firstValue("mcp-session-id").orElse(null), captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "XINGYUAN_ISOLATED_INTEGRATION", matches = "true")
    void actualMcpResourceReadEnforcesPublishedScopeAndExactFinePermission() throws Exception {
        var client = UnifiedIdentityLiveClient.isolated(); var mapper = UnifiedIdentityLiveClient.strictJson();
        Path manifestFile = Path.of(System.getProperty("xingyuan.isolated.manifest-file", System.getenv("XINGYUAN_INTEGRATION_MANIFEST")));
        byte[] raw = UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024); var manifest = mapper.readTree(raw);
        String digest = UnifiedIdentityLiveClient.sha256(raw), runId = manifest.path("runId").asText(); Path root = Path.of(manifest.path("runtimeRoot").asText());
        Path evidence = Path.of(System.getProperty("xingyuan.isolated.evidence-directory", root.resolve("evidence").toString())); requireEvidenceDirectory(root, evidence);
        var fixture = CiliumMcpFixture.from(manifest);
        var publication = requireFullPhase(root, evidence, manifest, digest, "mcp-publication.json", "COMMITTED");
        fixture.requirePublication(publication);
        Path ownerResult = evidence.resolve("mcp-authorization-execution.json");
        var owner = new ProcessBuilder("python3", "-I", Path.of(manifest.path("repoRoot").asText()).resolve("scripts/unified-xingyuan/lib/isolated_integration.py").toString(),
                "prepare-mcp-authorization", manifestFile.toString(), ownerResult.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!owner.waitFor(manifest.path("deadlines").path("operationSeconds").intValue(), TimeUnit.SECONDS)) { throw new IllegalStateException("XINGYUAN_COMMIT_UNKNOWN"); }
            if (owner.exitValue() != 0) {
                throw new IllegalStateException(switch (owner.exitValue()) {
                    case 3 -> "XINGYUAN_TARGET_UNSAFE"; case 4 -> "XINGYUAN_INPUT_UNAVAILABLE"; case 5 -> "XINGYUAN_STATE_CONFLICT";
                    case 6 -> "XINGYUAN_DEPENDENCY_UNAVAILABLE"; case 9 -> "XINGYUAN_COMMIT_UNKNOWN"; default -> "XINGYUAN_VERIFICATION_FAILED";
                });
            }
        } finally { if (owner.isAlive()) { owner.destroy(); owner.waitFor(10, TimeUnit.SECONDS); } }
        var execution = XingyuanNativeAuthenticationIT.requireNativeEvidence(root, evidence, manifest, digest, ownerResult.getFileName().toString());
        byte[] authorityBytes = UnifiedIdentityLiveClient.protectedBytes(evidence.resolve("mcp-authorization.json"), evidence, 128 * 1024); var authority = mapper.readTree(authorityBytes);
        if (!"MCP_TASK_AUTHORIZATION_OWNER_EXECUTION_ONLY".equals(execution.path("scope").asText())
                || !"top.egon.cola.platform.tianquan.jianshen.admin.integration.IsolatedAuthorizationProvisioningIT".equals(execution.path("execution").path("classname").asText())
                || transactionProofLong(execution.path("execution"), "testCount") != 1 || execution.path("execution").path("methods").size() != 1
                || !"provisionTaskMcpAuthorization".equals(execution.path("execution").path("methods").get(0).asText())
                || !root.relativize(evidence.resolve("mcp-authorization.json")).toString().equals(execution.path("proof").path("path").asText())
                || !UnifiedIdentityLiveClient.sha256(authorityBytes).equals(execution.path("proof").path("sha256").asText())
                || !("mcp:" + fixture.serverCode() + ":resource:scopes:read").equals(authority.path("permissionCode").asText())) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
        Duration timeout = Duration.ofSeconds(manifest.path("deadlines").path("httpSeconds").intValue());
        var output = mapper.createObjectNode().put("schemaVersion", 1).put("runId", runId).put("manifestSha256", digest)
                .put("scope", "TASK_MCP_REAL_RESOURCE_READ_SCOPE_AND_FINE_PERMISSION_ONLY"); var observations = output.putArray("observations");
        try (var http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()) {
            for (String realm : List.of("default", "tenant-b")) {
                var admin = client.formal(realm, fixture.resourceKey(), "admin"); var viewer = client.formal(realm, fixture.resourceKey(), "viewer");
                var wrong = client.formal(realm, fixture.foreignResourceKey(), "admin");
                client.verifiedClaims(admin); client.verifiedClaims(viewer); client.verifiedClaims(wrong);
                assertThat(wrong.getResourceUri()).isNotEqualTo(admin.getResourceUri());
                var scopeOnly = acquireIdentityOnlyProbe(client, root, evidence, manifestFile, manifest, digest, realm, fixture.resourceKey());
                var initialized = mcpDataRequest(http, timeout, root, fixture, admin, null, "initialize", null, 200);
                var viewerInitialized = mcpDataRequest(http, timeout, root, fixture, viewer, null, "initialize", null, 200);
                if (initialized.sessionId() == null || viewerInitialized.sessionId() == null || initialized.sessionId().equals(viewerInitialized.sessionId())) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                var requests = observations.addObject().put("realmCode", realm).put("tenantId", admin.getTenantId()).put("resourceUri", admin.getResourceUri().toASCIIString())
                        .put("adminReference", admin.getTokenSha256()).put("viewerReference", viewer.getTokenSha256()).put("identityOnlyReference", scopeOnly.getTokenSha256()).putArray("requests");
                var before = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", realm, "BUSINESS_COUNTS", null);
                for (int index = 0; index < 6; index++) {
                    var reference = index == 1 ? viewer : index == 2 ? scopeOnly : index == 3 ? wrong : index == 4 ? null : admin;
                    int status = index == 1 || index == 2 ? 403 : index == 3 || index == 4 ? 401 : 200;
                    var response = mcpDataRequest(http, timeout, root, fixture, reference, index == 1 ? viewerInitialized.sessionId() : initialized.sessionId(), "resources/read",
                            index == 5 ? client.formal(realm.equals("default") ? "tenant-b" : "default", fixture.resourceKey(), "admin").getTenantId() : null, status);
                    var after = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", realm, "BUSINESS_COUNTS", null);
                    if (status == 200) {
                        requireOneBusinessInvocation(before, after);
                        var data = mapper.readTree(response.body());
                        if (!data.path("result").path("contents").isArray() || data.path("result").path("contents").size() != 1
                                || !("egon://" + fixture.serverCode() + "/scopes").equals(data.path("result").path("contents").get(0).path("uri").asText())) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                    } else if (!before.equals(after)) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                    var item = requests.addObject().put("kind", List.of("SUCCESS", "FINE_DENIED", "SCOPE_DENIED", "WRONG_AUDIENCE", "MISSING", "RECOVERY").get(index))
                            .put("responseSha256", UnifiedIdentityLiveClient.sha256(response.body().getBytes(StandardCharsets.UTF_8)));
                    item.set("request", mapper.valueToTree(response.observation())); item.set("before", before); item.set("after", after); before = after;
                }
            }
        }
        requireRunningProcesses(root, manifestFile, manifest, digest, evidence, java.util.Set.of("yuheng", "mcp", "jianshen", "shoubing"));
        output.put("status", "PASSED").put("recordedAt", Instant.now().toString());
        XingyuanNativeAuthenticationIT.publishNewNativeEvidence(root, evidence.resolve("resource-mcp-boundaries.json"), output);
        observeHttpAuditOwner(root, evidence, manifestFile, manifest, digest, "mcp");
    }

    private static final String SCOPES_PATH = "/api/v1/yuheng/admin/scopes";
    private static final String CILIUM_AUTHORIZATION_PROOF = "resource-cilium-http-authorization.json";

    private record CiliumAuthorizationFixture(String exposureId, URI adminOrigin, URI publicUrl, String resourceKey,
            String foreignResourceKey) {
        static CiliumAuthorizationFixture from(JsonNode manifest) {
            var entry = manifest.path("ciliumAuthorization");
            var id = entry.path("exposureId").asText();
            if (!positiveDecimal(id)) {
                throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
            }
            URI admin = GatewayAdminTestClient.reviewedServiceOrigin(manifest.path("managementOrigins").path("yuheng").asText());
            if (entry.has("adminOrigin") && !admin.toASCIIString().equals(entry.path("adminOrigin").textValue())) {
                throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
            }
            return new CiliumAuthorizationFixture(id, admin,
                    reviewedHttps(entry.path("publicUrl").asText(), SCOPES_PATH),
                    XingyuanResourceAuthorizationIT.resourceKey(entry, "resourceKey"),
                    XingyuanResourceAuthorizationIT.resourceKey(entry, "foreignResourceKey"));
        }
    }

    private static URI reviewedHttps(String value, String path) {
        var uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || !uri.getHost().matches("[a-z][a-z0-9-]*(?:\\.[a-z0-9][a-z0-9-]*)+")
                || uri.getPort() != -1 || !path.equals(uri.getRawPath())
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
        }
        return uri;
    }

    private static String resourceKey(JsonNode entry, String field) {
        var value = entry.path(field).textValue();
        if (value == null || !value.matches("[a-z][a-z0-9-]{2,63}")) {
            throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
        }
        return value;
    }

    private static boolean positiveDecimal(String value) {
        return value != null && value.matches("[1-9][0-9]{0,18}") && new java.math.BigInteger(value).bitLength() < 64;
    }

    static void requireCurrentCiliumExposure(JsonNode receipt, String exposureId, URI approvedUrl) {
        String generation = receipt.path("desiredGeneration").textValue();
        var route = receipt.path("kubernetesRoute");
        String metadataGeneration = route.path("metadataGeneration").textValue();
        if (!positiveDecimal(exposureId) || !positiveDecimal(generation) || !positiveDecimal(metadataGeneration)
                || !exposureId.equals(receipt.path("exposureId").textValue())
                || !approvedUrl.toASCIIString().equals(receipt.path("url").textValue()) || !"GET".equals(receipt.path("method").textValue())
                || !"OPEN".equals(receipt.path("desiredState").textValue()) || !"OPEN".equals(receipt.path("observedState").textValue())
                || !"SYNCED".equals(receipt.path("reconcileStatus").textValue()) || !generation.equals(receipt.path("observedGeneration").textValue())
                || !"HTTPRoute".equals(route.path("kind").textValue()) || !"yuheng-routes".equals(route.path("namespace").textValue())
                || !("yuheng-exposure-" + exposureId).equals(route.path("name").textValue())
                || !route.path("uid").isTextual() || route.path("uid").textValue().isBlank()
                || !route.path("attached").isBoolean() || !route.path("attached").booleanValue()
                || !GatewayHttpRouteCompiler.CONTROLLER_NAME.equals(route.path("controllerName").textValue())
                || !metadataGeneration.equals(route.path("conditionObservedGeneration").textValue())
                || !receipt.path("conditions").isArray()) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
        for (String kind : List.of("Accepted", "ResolvedRefs", "Programmed", "DataplaneConverged")) {
            var matches = receipt.path("conditions").valueStream().filter(value -> kind.equals(value.path("type").textValue())).toList();
            if (matches.size() != 1 || !"True".equals(matches.getFirst().path("status").textValue())
                    || !generation.equals(matches.getFirst().path("observedGeneration").textValue())) {
                throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
            }
        }
    }

    private static String fixtureBearer(Path root, FormalReferenceDTO reference) throws IOException {
        byte[] bytes = UnifiedIdentityLiveClient.protectedBytes(reference.getTokenFile(), root.resolve("secrets"), 128 * 1024);
        try {
            if (!reference.getExpiresAt().isAfter(Instant.now()) || !reference.getTokenSha256().equals(UnifiedIdentityLiveClient.sha256(bytes))) {
                throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static JsonNode scopesRequest(HttpClient http, Duration timeout, Path root, URI url, FormalReferenceDTO reference,
            String tenantHeader, int expected) throws Exception {
        var request = HttpRequest.newBuilder(url).timeout(timeout).GET();
        if (reference != null) {
            request.header("Authorization", "Bearer " + fixtureBearer(root, reference));
        }
        if (tenantHeader != null) {
            request.header("X-Tenant-Id", tenantHeader);
        }
        var captured = new ByteArrayOutputStream();
        var response = http.send(request.build(), boundedBody(captured));
        assertThat(response.statusCode()).isEqualTo(expected);
        var body = UnifiedIdentityLiveClient.strictJson().readTree(captured.toByteArray());
        assertThat(body).isNotNull();
        if (expected == 200) {
            assertThat(body.isArray()).isTrue();
        }
        return body;
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "XINGYUAN_ISOLATED_INTEGRATION", matches = "true")
    void actualCiliumReadRequiresTheOriginalApplicationAuthorization() throws Exception {
        var client = UnifiedIdentityLiveClient.isolated();
        var mapper = UnifiedIdentityLiveClient.strictJson();
        Path manifestFile = Path.of(System.getProperty("xingyuan.isolated.manifest-file", System.getenv("XINGYUAN_INTEGRATION_MANIFEST")));
        byte[] raw = UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024);
        var manifest = mapper.readTree(raw);
        String digest = UnifiedIdentityLiveClient.sha256(raw);
        Path root = Path.of(manifest.path("runtimeRoot").asText());
        Path evidence = Path.of(System.getProperty("xingyuan.isolated.evidence-directory", root.resolve("evidence").toString()));
        requireEvidenceDirectory(root, evidence);
        var fixture = CiliumAuthorizationFixture.from(manifest);
        var owner = client.formal("default", fixture.resourceKey(), "admin");
        var control = new GatewayAdminTestClient(fixture.adminOrigin(), fixtureBearer(root, owner));
        var exposure = control.getExposure(fixture.exposureId());
        assertThat(exposure.status()).isEqualTo(200);
        requireCurrentCiliumExposure(exposure.body(), fixture.exposureId(), fixture.publicUrl());

        var output = mapper.createObjectNode().put("schemaVersion", 1).put("runId", manifest.path("runId").asText()).put("manifestSha256", digest)
                .put("scope", "CILIUM_HTTP_ROUTE_AND_APPLICATION_AUTHORIZATION")
                .put("exposureId", fixture.exposureId()).put("desiredGeneration", exposure.body().path("desiredGeneration").textValue())
                .put("routeUid", exposure.body().path("kubernetesRoute").path("uid").textValue());
        var identities = output.putArray("identities");
        Duration timeout = Duration.ofSeconds(manifest.path("deadlines").path("httpSeconds").intValue());
        try (var http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()) {
            for (String realm : List.of("default", "tenant-b")) {
                var admin = client.formal(realm, fixture.resourceKey(), "admin");
                var viewer = client.formal(realm, fixture.resourceKey(), "viewer");
                var foreign = client.formal(realm, fixture.foreignResourceKey(), "admin");
                var identityOnly = acquireIdentityOnlyProbe(client, root, evidence, manifestFile, manifest, digest, realm, fixture.resourceKey());
                assertThat(foreign.getResourceUri()).isNotEqualTo(admin.getResourceUri());
                var before = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", realm, "BUSINESS_COUNTS", null);
                var allowed = scopesRequest(http, timeout, root, fixture.publicUrl(), admin, null, 200);
                var after = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", realm, "BUSINESS_COUNTS", null);
                requireOneBusinessInvocation(before, after);
                var observation = identities.addObject().put("realmCode", realm).put("allowedStatus", 200);
                observation.set("before", before);
                observation.set("after", after);
                var denials = observation.putArray("denials");
                for (var denial : List.of(new ReadDenial("MISSING_TOKEN", null, 401), new ReadDenial("IDENTITY_ONLY_SCOPE", identityOnly, 403),
                        new ReadDenial("VIEWER_PDP", viewer, 403), new ReadDenial("FOREIGN_AUDIENCE", foreign, 401))) {
                    before = after;
                    scopesRequest(http, timeout, root, fixture.publicUrl(), denial.reference(), null, denial.status());
                    after = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", realm, "BUSINESS_COUNTS", null);
                    assertThat(after).isEqualTo(before);
                    denials.addObject().put("kind", denial.kind()).put("status", denial.status()).set("businessCounts", after);
                }
                var other = client.formal("default".equals(realm) ? "tenant-b" : "default", fixture.resourceKey(), "admin");
                var selected = scopesRequest(http, timeout, root, fixture.publicUrl(), admin, other.getTenantId(), 200);
                assertThat(selected).isEqualTo(allowed);
                var verified = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", realm, "BUSINESS_COUNTS", null);
                requireOneBusinessInvocation(after, verified);
                observation.put("originalTenantStatus", 200).set("originalTenantCounts", verified);
            }
        }
        var latest = control.getExposure(fixture.exposureId());
        assertThat(latest.status()).isEqualTo(200);
        requireCurrentCiliumExposure(latest.body(), fixture.exposureId(), fixture.publicUrl());
        assertThat(latest.body().path("desiredGeneration")).isEqualTo(exposure.body().path("desiredGeneration"));
        assertThat(latest.body().path("kubernetesRoute").path("uid")).isEqualTo(exposure.body().path("kubernetesRoute").path("uid"));
        output.put("status", "PASSED").put("recordedAt", Instant.now().toString());
        XingyuanNativeAuthenticationIT.publishNewNativeEvidence(root, evidence.resolve(CILIUM_AUTHORIZATION_PROOF), output);
    }

    private record ReadDenial(String kind, FormalReferenceDTO reference, int status) { }

    private static final java.util.Map<String, String> PLATFORM_READ_PATHS = java.util.Map.of(
            "shoubing", "/api/v1/tianquan-shoubing/clients", "jianshen", "/api/tianquan-jianshen/v1/iam/roles",
            "yuheng", "/api/v1/yuheng/admin/scopes", "tianshu", "/api/v1/tianshu/registry/services/page?pageNo=1&pageSize=10");

    @Test
    @EnabledIfEnvironmentVariable(named = "XINGYUAN_ISOLATED_INTEGRATION", matches = "true")
    void nativeUserInfoRequestsUseRealRealmTokensAndReturnAuditCorrelation() throws Exception {
        var client = UnifiedIdentityLiveClient.isolated(); var mapper = UnifiedIdentityLiveClient.strictJson();
        Path manifestFile = Path.of(System.getProperty("xingyuan.isolated.manifest-file", System.getenv("XINGYUAN_INTEGRATION_MANIFEST")));
        byte[] bytes = UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024); var manifest = mapper.readTree(bytes);
        String digest = UnifiedIdentityLiveClient.sha256(bytes); Path root = Path.of(manifest.path("runtimeRoot").asText());
        Path evidence = Path.of(System.getProperty("xingyuan.isolated.evidence-directory", root.resolve("evidence").toString())); requireEvidenceDirectory(root, evidence);
        requireRunningProcesses(root, manifestFile, manifest, digest, evidence, java.util.Set.of("shoubing"));
        var proof = mapper.createObjectNode().put("schemaVersion", 1).put("runId", manifest.path("runId").asText()).put("manifestSha256", digest)
                .put("scope", "NATIVE_USERINFO_HTTP_REQUESTS_ONLY"); var observations = proof.putArray("observations");
        for (String realm : List.of("default", "tenant-b")) {
            var reference = client.formal(realm, client.resourceForAlias("edge-shoubing"), "admin");
            if (!reference.getScopes().contains("openid")) { throw new IllegalStateException("XINGYUAN_INPUT_UNAVAILABLE"); }
            var requests = List.of(client.observeNativeUserInfo(reference, true), client.observeNativeUserInfo(reference, false), client.observeNativeUserInfo(reference, true));
            var item = observations.addObject().put("realmCode", realm).put("issuer", reference.getIssuer().toASCIIString()).put("resourceUri", reference.getResourceUri().toASCIIString())
                    .put("tokenReference", reference.getTokenSha256()); item.set("requests", mapper.valueToTree(requests));
        }
        proof.put("status", "PASSED").put("recordedAt", Instant.now().toString());
        XingyuanNativeAuthenticationIT.publishNewNativeEvidence(root, evidence.resolve("resource-native-userinfo.json"), proof);
        observeHttpAuditOwner(root, evidence, manifestFile, manifest, digest, "native-userinfo");
        requireRunningProcesses(root, manifestFile, manifest, digest, evidence, java.util.Set.of("shoubing"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "XINGYUAN_ISOLATED_INTEGRATION", matches = "true")
    void actualOwnedStopRetainsTheOriginalCohortAndBoundedPreservationEvidence() throws Exception {
        Path manifestFile = Path.of(System.getProperty("xingyuan.isolated.manifest-file", System.getenv("XINGYUAN_INTEGRATION_MANIFEST")));
        byte[] raw = UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024); var mapper = UnifiedIdentityLiveClient.strictJson();
        var manifest = mapper.readTree(raw); String digest = UnifiedIdentityLiveClient.sha256(raw); Path root = Path.of(manifest.path("runtimeRoot").asText());
        Path evidence = Path.of(System.getProperty("xingyuan.isolated.evidence-directory")); requireEvidenceDirectory(root, evidence);
        Path result = evidence.resolve("resource-owned-stop-preservation.json");
        var stop = new ProcessBuilder("python3", "-I", Path.of(manifest.path("repoRoot").asText()).resolve("scripts/unified-xingyuan/lib/isolated_integration.py").toString(),
                "owned-stop-preservation", manifestFile.toString(), result.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!stop.waitFor(manifest.path("deadlines").path("operationSeconds").intValue(), TimeUnit.SECONDS) || stop.exitValue() != 0) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        } finally { if (stop.isAlive()) { stop.destroy(); stop.waitFor(10, TimeUnit.SECONDS); } }
        var proof = XingyuanNativeAuthenticationIT.requireNativeEvidence(root, evidence, manifest, digest, result.getFileName().toString());
        int expected = manifest.path("processes").size() - (UnifiedIdentityLiveClient.activeNativeRuntimeRoles(root, manifest, digest).contains("native-agent") ? 0 : 1);
        if (!"ORIGINAL_OWNED_COHORT_STOP_AND_BOUNDED_PRESERVATION_ONLY".equals(proof.path("scope").asText())
                || transactionProofLong(proof, "ownedProcessCount") != expected || transactionProofLong(proof, "retainedCaseCount") != 23
                || transactionProofLong(proof, "retainedSecretFileCount") != manifest.path("secretFiles").size()
                || transactionProofLong(proof, "retainedDatabaseReceiptCount") != 9 || transactionProofLong(proof, "retainedDatabaseIdentityCount") != 9
                || !proof.path("proofs").isArray() || proof.path("proofs").size() != 4) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        var expectedFiles = java.util.Set.of("owned-stop.json", "owned-preservation/original-databases-preserved.json",
                "owned-preservation/original-nginx-preserved.json", "owned-preservation/original-redis-preserved.json");
        var actual = new java.util.HashSet<String>();
        for (var reference : proof.path("proofs")) {
            Path path = root.resolve(reference.path("path").asText());
            if (!path.startsWith(evidence) || !path.normalize().equals(path) || !actual.add(evidence.relativize(path).toString())
                    || !UnifiedIdentityLiveClient.sha256(UnifiedIdentityLiveClient.protectedBytes(path, evidence, 128 * 1024)).equals(reference.path("sha256").asText())) {
                throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
            }
        }
        if (!actual.equals(expectedFiles) || !digest.equals(UnifiedIdentityLiveClient.sha256(UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024)))) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "XINGYUAN_ISOLATED_INTEGRATION", matches = "true")
    void platformHostsRejectTamperingAndForeignAudienceBeforeTheirOriginalBusiness() throws Exception {
        var client = UnifiedIdentityLiveClient.isolated(); var mapper = UnifiedIdentityLiveClient.strictJson();
        Path manifestFile = Path.of(System.getProperty("xingyuan.isolated.manifest-file", System.getenv("XINGYUAN_INTEGRATION_MANIFEST")));
        byte[] manifestBytes = UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024); var manifest = mapper.readTree(manifestBytes);
        String digest = UnifiedIdentityLiveClient.sha256(manifestBytes); Path root = Path.of(manifest.path("runtimeRoot").asText());
        Path evidence = Path.of(System.getProperty("xingyuan.isolated.evidence-directory", root.resolve("evidence").toString()));
        requireEvidenceDirectory(root, evidence);
        requireRunningProcesses(root, manifestFile, manifest, digest, evidence, java.util.Set.of("shoubing", "jianshen", "yuheng", "tianshu"));
        var proof = mapper.createObjectNode().put("schemaVersion", 1).put("runId", manifest.path("runId").asText()).put("manifestSha256", digest)
                .put("scope", "FOUR_PLATFORM_DIRECT_HTTP_AUTHENTICATION_AND_COUNTERS"); var observations = proof.putArray("observations");
        Duration timeout = Duration.ofSeconds(manifest.path("deadlines").path("httpSeconds").intValue());
        try (var http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()) {
            for (String realm : List.of("default", "tenant-b")) {
                for (String role : List.of("shoubing", "jianshen", "yuheng", "tianshu")) {
                    var reference = client.formal(realm, client.resourceForAlias("edge-" + role), "admin"); client.verifiedClaims(reference);
                    var viewer = client.formal(realm, reference.getResourceKey(), "viewer"); client.verifiedClaims(viewer);
                    String foreignRole = role.equals("yuheng") ? "shoubing" : "yuheng";
                    var foreign = client.formal(realm, client.resourceForAlias("edge-" + foreignRole), "admin"); client.verifiedClaims(foreign);
                    if (reference.getResourceUri().equals(foreign.getResourceUri())) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
                    var api = new NativeResourceHttpProbe(role, root, manifestFile, digest, http, timeout);
                    var calls = new ArrayList<HttpAuditObservationVO>();
                    var before = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, role, realm, "BUSINESS_COUNTS", null);
                    api.request("GET", PLATFORM_READ_PATHS.get(role), reference, null, null, 200, calls::add);
                    var successful = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, role, realm, "BUSINESS_COUNTS", null);
                    requireOneBusinessInvocation(before, successful);
                    api.request("GET", PLATFORM_READ_PATHS.get(role), viewer, null, null, 403, calls::add);
                    var scopeOnly = acquireIdentityOnlyProbe(client, root, evidence, manifestFile, manifest, digest, realm, reference.getResourceKey());
                    api.request("GET", PLATFORM_READ_PATHS.get(role), scopeOnly, null, null, 403, calls::add);
                    api.request("GET", PLATFORM_READ_PATHS.get(role), foreign, null, null, 401, calls::add);
                    api.request("GET", PLATFORM_READ_PATHS.get(role), null, null, null, 401, calls::add);
                    for (String mutation : List.of("SIGNATURE", "ISSUER", "TYPE", "DUPLICATE")) {
                        calls.add(platformTamperedRequest(http, timeout, root, reference, nativeResourceEndpoint(manifest, role, "GET", PLATFORM_READ_PATHS.get(role)), mutation));
                    }
                    var denied = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, role, realm, "BUSINESS_COUNTS", null);
                    if (!successful.equals(denied)) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                    api.request("GET", PLATFORM_READ_PATHS.get(role), reference, null, null, 200, calls::add);
                    var recovered = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, role, realm, "BUSINESS_COUNTS", null);
                    requireOneBusinessInvocation(denied, recovered);
                    var item = observations.addObject().put("role", role).put("realmCode", realm).put("tenantId", reference.getTenantId())
                            .put("tokenReference", reference.getTokenSha256()).put("viewerReference", viewer.getTokenSha256()).put("foreignAudienceReference", foreign.getTokenSha256())
                            .put("identityOnlyTokenReference", scopeOnly.getTokenSha256()).put("identityOnlyExpiresAt", scopeOnly.getExpiresAt().toString())
                            .put("successStatus", 200).put("pdpDeniedStatus", 403).put("authenticationDeniedStatus", 401).put("tamperedRequestCount", 4);
                    item.set("before", before); item.set("successful", successful); item.set("denied", denied); item.set("recovered", recovered);
                    item.set("requests", mapper.valueToTree(calls));
                    item.set("identityOnlyScopes", mapper.valueToTree(scopeOnly.getScopes()));
                }
            }
        }
        requireRunningProcesses(root, manifestFile, manifest, digest, evidence, java.util.Set.of("shoubing", "jianshen", "yuheng", "tianshu"));
        proof.put("status", "PASSED").put("recordedAt", Instant.now().toString());
        XingyuanNativeAuthenticationIT.publishNewNativeEvidence(root, evidence.resolve("resource-platform-http-boundaries.json"), proof);
        observePlatformHttpAuditOwner(root, evidence, manifestFile, manifest, digest);
        requireRunningProcesses(root, manifestFile, manifest, digest, evidence, java.util.Set.of("shoubing", "jianshen", "yuheng", "tianshu"));
    }

    private static FormalReferenceDTO acquireIdentityOnlyProbe(UnifiedIdentityLiveClient.IsolatedResourceProbe client, Path root, Path evidence,
            Path manifestFile, JsonNode manifest, String digest, String realm, String resource) throws Exception {
        if (!java.util.Set.of("default", "tenant-b").contains(realm) || !resource.matches("[a-z][a-z0-9-]{2,63}")) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        Path canonical = root.resolve("secrets/token-index.json"); byte[] before = UnifiedIdentityLiveClient.protectedBytes(canonical, root.resolve("secrets"), 128 * 1024);
        String nonce = UUID.randomUUID().toString(); Path token = root.resolve("secrets/scope-probe-" + nonce + ".at"), result = evidence.resolve("scope-probe-" + nonce + ".json");
        var process = new ProcessBuilder("python3", "-I", Path.of(manifest.path("repoRoot").asText()).resolve("scripts/unified-xingyuan/lib/isolated_integration.py").toString(),
                "authorize-scope-probe", manifestFile.toString(), result.toString(), realm, resource, "admin", token.toString(), manifest.path("repoRoot").asText())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(manifest.path("deadlines").path("operationSeconds").intValue(), TimeUnit.SECONDS) || process.exitValue() != 0) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        } finally { if (process.isAlive()) { process.destroy(); process.waitFor(10, TimeUnit.SECONDS); } }
        var outcome = UnifiedIdentityLiveClient.strictJson().readTree(UnifiedIdentityLiveClient.protectedBytes(result, evidence, 128 * 1024));
        if (!"CLI-006".equals(outcome.path("operationId").asText()) || !"COMMITTED".equals(outcome.path("status").asText()) || !outcome.path("error").isNull()
                || !manifest.path("runId").asText().equals(outcome.path("runId").asText()) || !digest.equals(outcome.path("manifestSha256").asText())
                || !java.util.Arrays.equals(before, UnifiedIdentityLiveClient.protectedBytes(canonical, root.resolve("secrets"), 128 * 1024))) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
        var reference = client.identityOnly(realm, resource, Path.of(token + ".metadata.json"));
        if (!reference.getScopes().equals(java.util.Set.of("openid"))) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        return reference;
    }

    private static void observePlatformHttpAuditOwner(Path root, Path evidence, Path manifestFile, JsonNode manifest, String digest) throws Exception {
        observeHttpAuditOwner(root, evidence, manifestFile, manifest, digest, "platform-http");
    }

    static void observeNativeRpcAuditOwner(Path root, Path evidence, Path manifestFile, JsonNode manifest, String digest) throws Exception {
        observeHttpAuditOwner(root, evidence, manifestFile, manifest, digest, "native-rpc");
    }

    private static void observeHttpAuditOwner(Path root, Path evidence, Path manifestFile, JsonNode manifest, String digest, String protocol) throws Exception {
        if (!java.util.Set.of("platform-http", "native-userinfo", "native-rpc", "mcp").contains(protocol)) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        String stage = protocol + "-audit";
        String method = switch (protocol) { case "mcp" -> "observeMcpHttpAudits"; case "native-rpc" -> "observeNativeRpcAudits"; case "native-userinfo" -> "observeNativeUserInfoAudits"; default -> "observePlatformHttpAudits"; };
        String sourceName = switch (protocol) { case "mcp" -> "resource-mcp-boundaries.json"; case "native-rpc" -> "native-rpc-contract-probe.json"; case "native-userinfo" -> "resource-native-userinfo.json"; default -> "resource-platform-http-boundaries.json"; };
        String proofScope = switch (protocol) { case "mcp" -> "MCP_HTTP_PG_STREAM_SIGNED_RECEIPTS_ONLY"; case "native-rpc" -> "NATIVE_RPC_PG_STREAM_SIGNED_RECEIPTS_ONLY"; case "native-userinfo" -> "NATIVE_USERINFO_PG_STREAM_SIGNED_RECEIPTS_ONLY"; default -> "FOUR_PLATFORM_HTTP_PG_STREAM_SIGNED_RECEIPTS_ONLY"; };
        long requests = protocol.equals("mcp") ? 12 : protocol.equals("native-rpc") ? 16 : protocol.equals("native-userinfo") ? 6 : 80;
        long events = protocol.equals("mcp") || protocol.equals("native-rpc") ? 20 : protocol.equals("native-userinfo") ? 6 : 96;
        Path result = evidence.resolve(stage + "-owner.json");
        var command = new ProcessBuilder("python3", "-I", Path.of(manifest.path("repoRoot").asText()).resolve("scripts/unified-xingyuan/lib/isolated_integration.py").toString(),
                "observe-" + protocol + "-audits", manifestFile.toString(), result.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!command.waitFor(manifest.path("deadlines").path("operationSeconds").intValue(), TimeUnit.SECONDS) || command.exitValue() != 0) {
                throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
            }
        } finally {
            if (command.isAlive()) { command.destroy(); command.waitFor(10, TimeUnit.SECONDS); }
        }
        var mapper = UnifiedIdentityLiveClient.strictJson(); var receipt = mapper.readTree(UnifiedIdentityLiveClient.protectedBytes(result, evidence, 128 * 1024));
        byte[] source = UnifiedIdentityLiveClient.protectedBytes(evidence.resolve(sourceName), evidence, 128 * 1024);
        Path proofFile = evidence.resolve("protocol-" + protocol + "-audits.json");
        byte[] proofBytes = UnifiedIdentityLiveClient.protectedBytes(proofFile, evidence, 128 * 1024);
        var execution = receipt.path("execution");
        if (!manifest.path("runId").asText().equals(receipt.path("runId").asText()) || !digest.equals(receipt.path("manifestSha256").asText())
                || !"PASSED".equals(receipt.path("status").asText()) || !(stage.toUpperCase(java.util.Locale.ROOT).replace('-', '_') + "_OWNER_EXECUTION_ONLY").equals(receipt.path("scope").asText())
                || !UnifiedIdentityLiveClient.sha256(source).equals(receipt.path("sourceSha256").asText())
                || !root.relativize(proofFile).toString().equals(receipt.path("proof").path("path").asText())
                || !UnifiedIdentityLiveClient.sha256(proofBytes).equals(receipt.path("proof").path("sha256").asText())
                || !"top.egon.cola.platform.tianquan.shoubing.admin.integration.IsolatedOAuthProvisioningIT".equals(execution.path("classname").asText())
                || !execution.path("testCount").isIntegralNumber() || execution.path("testCount").intValue() != 1
                || !execution.path("methods").isArray() || execution.path("methods").size() != 1 || !method.equals(execution.path("methods").get(0).asText())
                || !execution.path("reportSha256").asText().matches("[0-9a-f]{64}")) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        var proof = mapper.readTree(proofBytes);
        if (!"PASSED".equals(proof.path("status").asText()) || !manifest.path("runId").asText().equals(proof.path("runId").asText())
                || !digest.equals(proof.path("manifestSha256").asText()) || !UnifiedIdentityLiveClient.sha256(source).equals(proof.path("sourceSha256").asText())
                || !proofScope.equals(proof.path("scope").asText()) || transactionProofLong(proof, "requestCount") != requests
                || transactionProofLong(proof, "storedEventCount") != events || transactionProofLong(proof, "spoolAbsentAfterReceiptCount") != events) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
    }

    private static void requireOneBusinessInvocation(JsonNode before, JsonNode after) {
        if (before.path("counts").size() != 1 || after.path("counts").size() != 1) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        String method = before.path("counts").fieldNames().next();
        if (Math.addExact(transactionProofLong(before.path("counts"), method), 1) != transactionProofLong(after.path("counts"), method)) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
    }

    private static HttpAuditObservationVO platformTamperedRequest(HttpClient http, Duration timeout, Path root, FormalReferenceDTO reference, URI endpoint, String mutation) throws Exception {
        byte[] bytes = UnifiedIdentityLiveClient.protectedBytes(reference.getTokenFile(), root.resolve("secrets"), 128 * 1024);
        try {
            if (!reference.getExpiresAt().isAfter(Instant.now()) || !reference.getTokenSha256().equals(UnifiedIdentityLiveClient.sha256(bytes))) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
            String trace = UUID.randomUUID().toString().replace("-", ""); Instant started = Instant.now();
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout).GET().header("X-Request-Id", UUID.randomUUID().toString())
                    .header("traceparent", "00-" + trace + '-' + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "-01");
            String token = new String(bytes, StandardCharsets.UTF_8);
            request.header("Authorization", "Bearer " + (mutation.equals("DUPLICATE") ? token : tamperedBearer(token, mutation)));
            if (mutation.equals("DUPLICATE")) { request.header("Authorization", "Bearer " + token); }
            var response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 401 || response.headers().firstValue("Location").isPresent()
                    || response.headers().firstValue("Set-Cookie").isPresent()
                    || !response.headers().allValues("Cache-Control").stream().anyMatch(value -> value.contains("no-store"))) {
                throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
            }
            return observedHttpCall(response, trace, started);
        } finally { java.util.Arrays.fill(bytes, (byte) 0); }
    }

    @Test
    void httpAuditCorrelationRequiresTheActualSingleReturnedTrace() throws Exception {
        java.util.function.Function<List<String>, HttpResponse<Void>> response = values -> new HttpResponse<>() {
            @Override public int statusCode() { return 401; }
            @Override public HttpRequest request() { return HttpRequest.newBuilder(URI.create("https://127.0.0.1:28140/api/v1/yuheng/admin/scopes")).GET().build(); }
            @Override public java.util.Optional<HttpResponse<Void>> previousResponse() { return java.util.Optional.empty(); }
            @Override public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(java.util.Map.of("traceparent", values), (key, value) -> true); }
            @Override public Void body() { return null; }
            @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            @Override public URI uri() { return request().uri(); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
        String trace = "a".repeat(32), header = "00-" + trace + '-' + "b".repeat(16) + "-01";
        var observed = observedHttpCall(response.apply(List.of(header)), trace, Instant.now().minusSeconds(1));
        assertThat(observed.traceId()).isEqualTo(trace); assertThat(observed.status()).isEqualTo(401);
        assertThat(UnifiedIdentityLiveClient.strictJson().valueToTree(observed).size()).isEqualTo(4);
        for (var invalid : List.of(List.<String>of(), List.of(header, header), List.of(header.replace(trace, "c".repeat(32))),
                List.of(header.replace("b".repeat(16), "0".repeat(16))), List.of("malformed"))) {
            assertThatThrownBy(() -> observedHttpCall(response.apply(invalid), trace, Instant.now().minusSeconds(1)))
                    .hasMessage("XINGYUAN_VERIFICATION_FAILED");
        }
    }

    static String tamperedBearer(String original, String mutation) throws Exception {
        String[] parts = original.split("\\.", -1);
        if (!java.util.Set.of("SIGNATURE", "ISSUER", "TYPE").contains(mutation) || parts.length != 3
                || java.util.Arrays.stream(parts).anyMatch(value -> !value.matches("[A-Za-z0-9_-]{1,131072}"))) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        var decoder = java.util.Base64.getUrlDecoder(); var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        if (mutation.equals("SIGNATURE")) {
            byte[] signature = decoder.decode(parts[2]); signature[0] ^= 1; parts[2] = encoder.encodeToString(signature);
        } else {
            int selected = mutation.equals("TYPE") ? 0 : 1;
            var value = UnifiedIdentityLiveClient.strictJson().readTree(decoder.decode(parts[selected]));
            if (!value.isObject()) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
            ((com.fasterxml.jackson.databind.node.ObjectNode) value).put(mutation.equals("TYPE") ? "typ" : "iss", mutation.equals("TYPE") ? "JWT" : "https://foreign.invalid/platform");
            parts[selected] = encoder.encodeToString(UnifiedIdentityLiveClient.strictJson().writeValueAsBytes(value));
        }
        return String.join(".", parts);
    }

    @Test
    void bearerTamperingNeverSignsOrReplacesAnUnchangedRequest() throws Exception {
        var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"typ\":\"at+jwt\",\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = encoder.encodeToString("{\"iss\":\"https://owned.invalid/tenants/1\"}".getBytes(StandardCharsets.UTF_8));
        String signature = encoder.encodeToString(new byte[] {1, 2, 3, 4}); String original = header + '.' + claims + '.' + signature;
        for (String mutation : List.of("SIGNATURE", "ISSUER", "TYPE")) {
            var changed = tamperedBearer(original, mutation).split("\\."); assertThat(String.join(".", changed)).isNotEqualTo(original);
            if (mutation.equals("SIGNATURE")) { assertThat(changed[0]).isEqualTo(header); assertThat(changed[1]).isEqualTo(claims); }
            else { assertThat(changed[2]).isEqualTo(signature); }
        }
        assertThatThrownBy(() -> tamperedBearer(original, "SIGN")).hasMessage("XINGYUAN_TARGET_UNSAFE");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "XINGYUAN_ISOLATED_INTEGRATION", matches = "true")
    void threeIssuerMetadataUsesDistinctPublicKeysAndRetiresTheRoot() throws Exception {
        // Reuse the original manifest, registry, profile and trusted-issuer input gate before HTTP.
        UnifiedIdentityLiveClient.isolated();
        var mapper = UnifiedIdentityLiveClient.strictJson();
        Path manifestFile = Path.of(System.getProperty("xingyuan.isolated.manifest-file", System.getenv("XINGYUAN_INTEGRATION_MANIFEST")));
        byte[] manifestBytes = UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024);
        var manifest = mapper.readTree(manifestBytes); String digest = UnifiedIdentityLiveClient.sha256(manifestBytes);
        Path root = Path.of(manifest.path("runtimeRoot").asText());
        Path evidence = Path.of(System.getProperty("xingyuan.isolated.evidence-directory", root.resolve("evidence").toString()));
        requireEvidenceDirectory(root, evidence);
        Path registryFile = root.resolve("secrets/runtime-registry.json");
        byte[] registryBytes = UnifiedIdentityLiveClient.protectedBytes(registryFile, root.resolve("secrets"), 2 * 1024 * 1024);
        var registry = mapper.readTree(registryBytes);
        String origin = UnifiedIdentityLiveClient.ciliumOrigin(manifest, "shoubing").toASCIIString();
        if (!origin.equals(registry.path("publicOrigin").asText())
                || !digest.equals(registry.path("manifestSha256").asText()) || !manifest.path("runId").asText().equals(registry.path("runId").asText())
                || registry.path("realms").size() != 3 || registry.path("tenantIds").size() != 2) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        requireRunningProcesses(root, manifestFile, manifest, digest, evidence, java.util.Set.of("shoubing"));
        var proof = mapper.createObjectNode().put("schemaVersion", 1).put("runId", manifest.path("runId").asText())
                .put("manifestSha256", digest).put("scope", "THREE_ISSUER_PROTOCOL_ONLY");
        var observations = proof.putArray("issuers");
        var signingKeys = new ArrayList<java.util.Map<String, String>>();
        Duration timeout = Duration.ofSeconds(manifest.path("deadlines").path("httpSeconds").intValue());
        try (var transport = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()) {
            for (String realm : List.of("platform", "default", "tenant-b")) {
                String suffix = realm.equals("platform") ? "/platform" : "/tenants/" + registry.path("tenantIds").path(realm).asText();
                if (!suffix.equals("/platform") && !suffix.matches("/tenants/[1-9][0-9]{0,18}")) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
                String issuer = origin + suffix;
                if (!issuer.equals(registry.path("realms").path(realm).path("issuer").asText())) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
                var metadata = protocolResponse(transport, URI.create(issuer + "/.well-known/openid-configuration"), timeout, false, 200);
                requireCanonicalIssuerMetadata(issuer, metadata);
                var forwarded = protocolResponse(transport, URI.create(issuer + "/.well-known/openid-configuration"), timeout, true, 200);
                requireCanonicalIssuerMetadata(issuer, forwarded);
                var jwks = protocolResponse(transport, URI.create(issuer + "/.well-known/jwks.json"), timeout, false, 200);
                var keys = verifiedIssuerPublicKeys(jwks); signingKeys.add(keys);
                var item = observations.addObject().put("realmCode", realm).put("issuer", issuer)
                        .put("metadataReference", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(metadata)))
                        .put("forwardedReference", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(forwarded)))
                        .put("jwksReference", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(jwks)));
                var references = item.putArray("signingKeyReferences"); keys.values().stream().sorted().forEach(references::add);
            }
            requireDistinctIssuerKeys(signingKeys);
            var retired = protocolResponse(transport, URI.create(origin + "/.well-known/oauth-authorization-server"), timeout, false, 410);
            if (!"legacy_endpoint_retired".equals(retired.path("error").asText())) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
            proof.put("retiredRootStatus", 410).put("retiredRootReference", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(retired)));
        }
        if (!java.util.Arrays.equals(manifestBytes, UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024))
                || !java.util.Arrays.equals(registryBytes, UnifiedIdentityLiveClient.protectedBytes(registryFile, root.resolve("secrets"), 2 * 1024 * 1024))) {
            throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
        }
        proof.put("status", "PASSED").put("recordedAt", Instant.now().toString());
        publishResourceEvidence(root, evidence, "resource-issuer-discovery.json", proof);
    }

    private static JsonNode protocolResponse(HttpClient http, URI endpoint, Duration timeout, boolean forwarded, int expected) throws Exception {
        if (!"https".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()) || endpoint.getPort() != 28443
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !endpoint.getPath().matches("(?:(?:/platform|/tenants/[1-9][0-9]{0,18})/\\.well-known/(?:openid-configuration|jwks\\.json)|/\\.well-known/oauth-authorization-server)")) {
            throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
        }
        var request = HttpRequest.newBuilder(endpoint).timeout(timeout).GET();
        if (forwarded) { request.header("Forwarded", "host=foreign.invalid;proto=http").header("X-Forwarded-Host", "foreign.invalid").header("X-Forwarded-Proto", "http"); }
        var output = new ByteArrayOutputStream(); var response = http.send(request.build(), boundedBody(output));
        if (response.statusCode() != expected || response.headers().firstValue("Location").isPresent()
                || response.headers().firstValue("Set-Cookie").isPresent()
                || !response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
        return UnifiedIdentityLiveClient.strictJson().readTree(output.toByteArray());
    }

    static void requireCanonicalIssuerMetadata(String issuer, JsonNode value) {
        if (value == null || !value.isObject() || !issuer.equals(value.path("issuer").asText())) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        for (var field : java.util.Map.of("authorization_endpoint", "/oauth2/authorize", "token_endpoint", "/oauth2/token",
                "userinfo_endpoint", "/userinfo", "jwks_uri", "/.well-known/jwks.json").entrySet()) {
            if (!value.path(field.getKey()).isTextual() || !(issuer + field.getValue()).equals(value.path(field.getKey()).textValue())) {
                throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
            }
        }
        var algorithms = value.path("id_token_signing_alg_values_supported");
        if (!algorithms.isArray() || algorithms.isEmpty() || java.util.stream.StreamSupport.stream(algorithms.spliterator(), false)
                .anyMatch(algorithm -> !algorithm.isTextual() || !"RS256".equals(algorithm.textValue()))) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
    }

    static java.util.Map<String, String> verifiedIssuerPublicKeys(JsonNode document) {
        if (document == null || !document.isObject() || !document.path("keys").isArray() || document.path("keys").isEmpty()
                || document.path("keys").size() > 16) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        var result = new java.util.LinkedHashMap<String, String>();
        for (var key : document.path("keys")) {
            String id = key.path("kid").asText(), modulus = key.path("n").asText(), exponent = key.path("e").asText();
            if (!key.isObject() || !"RSA".equals(key.path("kty").asText()) || !"RS256".equals(key.path("alg").asText()) || !"sig".equals(key.path("use").asText())
                    || !key.path("kid").isTextual() || id.isBlank() || id.length() > 128 || id.chars().anyMatch(Character::isISOControl)
                    || !key.path("n").isTextual() || !modulus.matches("[A-Za-z0-9_-]{1,16384}") || !key.path("e").isTextual() || !exponent.matches("[A-Za-z0-9_-]{1,16}")
                    || java.util.stream.Stream.of("d", "p", "q", "dp", "dq", "qi", "oth").anyMatch(key::has)) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
            try {
                var decoder = java.util.Base64.getUrlDecoder();
                var n = new java.math.BigInteger(1, decoder.decode(modulus)); var e = new java.math.BigInteger(1, decoder.decode(exponent));
                if (n.bitLength() < 3072 || !n.testBit(0) || e.compareTo(java.math.BigInteger.valueOf(3)) < 0 || !e.testBit(0)) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                String reference = UnifiedIdentityLiveClient.sha256((n.toString(16) + ":" + e.toString(16)).getBytes(StandardCharsets.US_ASCII));
                if (result.containsValue(reference) || result.putIfAbsent(id, reference) != null) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
            } catch (IllegalArgumentException invalid) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED", invalid); }
        }
        return java.util.Map.copyOf(result);
    }

    static void requireDistinctIssuerKeys(List<java.util.Map<String, String>> realms) {
        if (realms == null || realms.size() != 3) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        var ids = new java.util.HashSet<String>(); var keys = new java.util.HashSet<String>();
        for (var realm : realms) {
            if (realm == null || realm.isEmpty()) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
            for (var entry : realm.entrySet()) {
                if (!ids.add(entry.getKey()) || !keys.add(entry.getValue())) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
            }
        }
    }

    private static void publishResourceEvidence(Path root, Path evidence, String filename, JsonNode proof) throws Exception {
        requireEvidenceDirectory(root, evidence);
        if (!"resource-issuer-discovery.json".equals(filename)) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        Path temporary = Files.createTempFile(evidence, ".resource-protocol-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (var output = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                var bytes = java.nio.ByteBuffer.wrap(UnifiedIdentityLiveClient.strictJson().writeValueAsBytes(proof));
                while (bytes.hasRemaining()) { output.write(bytes); }
                output.force(true);
            }
            Files.createLink(evidence.resolve(filename), temporary);
            try (var directory = java.nio.channels.FileChannel.open(evidence, java.nio.file.StandardOpenOption.READ)) { directory.force(true); }
        } finally { Files.deleteIfExists(temporary); }
    }

    @Test
    void issuerMetadataRejectsForeignCanonicalEndpointsAndAlgorithms() {
        var mapper = UnifiedIdentityLiveClient.strictJson(); String issuer = "https://127.0.0.1:28443/tenants/1001";
        var metadata = mapper.createObjectNode().put("issuer", issuer).put("authorization_endpoint", issuer + "/oauth2/authorize")
                .put("token_endpoint", issuer + "/oauth2/token").put("userinfo_endpoint", issuer + "/userinfo").put("jwks_uri", issuer + "/.well-known/jwks.json");
        metadata.putArray("id_token_signing_alg_values_supported").add("RS256");
        assertThatCode(() -> requireCanonicalIssuerMetadata(issuer, metadata)).doesNotThrowAnyException();
        for (String field : List.of("issuer", "authorization_endpoint", "token_endpoint", "userinfo_endpoint", "jwks_uri")) {
            var changed = metadata.deepCopy().put(field, "https://foreign.invalid");
            assertThatThrownBy(() -> requireCanonicalIssuerMetadata(issuer, changed)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        }
        metadata.withArray("id_token_signing_alg_values_supported").add("none");
        assertThatThrownBy(() -> requireCanonicalIssuerMetadata(issuer, metadata)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
    }

    @Test
    void issuerKeysRejectPrivateWeakDuplicateAndCrossRealmMaterial() {
        var mapper = UnifiedIdentityLiveClient.strictJson(); var document = mapper.createObjectNode();
        String modulus = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.math.BigInteger.ONE.shiftLeft(3071).add(java.math.BigInteger.valueOf(17)).toByteArray());
        var key = document.putArray("keys").addObject().put("kty", "RSA").put("alg", "RS256").put("use", "sig").put("kid", "realm-a").put("n", modulus).put("e", "AQAB");
        var original = verifiedIssuerPublicKeys(document); assertThat(original).hasSize(1);
        var privateKey = document.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) privateKey.path("keys").get(0)).put("d", "private");
        assertThatThrownBy(() -> verifiedIssuerPublicKeys(privateKey)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        var weak = document.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) weak.path("keys").get(0)).put("n", "AQAB");
        assertThatThrownBy(() -> verifiedIssuerPublicKeys(weak)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        document.withArray("keys").add(key.deepCopy().put("kid", "same-material"));
        assertThatThrownBy(() -> verifiedIssuerPublicKeys(document)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        assertThatCode(() -> requireDistinctIssuerKeys(List.of(java.util.Map.of("a", "key-a"), java.util.Map.of("b", "key-b"), java.util.Map.of("c", "key-c")))).doesNotThrowAnyException();
        assertThatThrownBy(() -> requireDistinctIssuerKeys(List.of(original, java.util.Map.of("b", original.values().iterator().next()), java.util.Map.of("c", "key-c"))))
                .hasMessage("XINGYUAN_VERIFICATION_FAILED");
        assertThatThrownBy(() -> requireDistinctIssuerKeys(List.of(original, original, java.util.Map.of("c", "key-c")))).hasMessage("XINGYUAN_VERIFICATION_FAILED");
    }

    /** This is only the JPA/MP portion of LIVE002; the identity transaction cases remain separate. */
    static List<Path> readJpaMpTransactionProofs(Path root, Path evidence, JsonNode manifest, String digest) throws Exception {
        requireEvidenceDirectory(root, evidence);
        if (!root.resolve("evidence").equals(evidence.getParent()) || !evidence.getFileName().toString().matches("verify-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
        }
        var mapper = UnifiedIdentityLiveClient.strictJson(); String runId = manifest.path("runId").asText();
        var attempt = mapper.readTree(UnifiedIdentityLiveClient.protectedBytes(evidence.resolve("intent.json"), evidence, 128 * 1024));
        var database = mapper.readTree(UnifiedIdentityLiveClient.protectedBytes(root.resolve("evidence/db-rbac.json"), root.resolve("evidence"), 128 * 1024));
        if (!runId.equals(attempt.path("runId").asText()) || !digest.equals(attempt.path("manifestSha256").asText())
                || !"full".equals(attempt.path("caseSet").asText()) || !runId.equals(database.path("runId").asText()) || !digest.equals(database.path("manifestSha256").asText())
                || !"rbac".equals(database.path("role").asText()) || !"xingyuan_it_rbac_20261011".equals(database.path("databaseName").asText())
                || !("xingyuan-it:" + runId + ":" + digest).equals(database.path("ownerComment").asText())) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        long oid = transactionProofLong(database, "oid");
        Instant attemptStartedAt = transactionProofInstant(attempt, "startedAt");
        long operationSeconds = transactionProofLong(manifest.path("deadlines"), "operationSeconds");
        if (operationSeconds < 1L || operationSeconds > 900L || attemptStartedAt.isBefore(Instant.now().minusSeconds(operationSeconds))) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
        var proofs = new ArrayList<JsonNode>(); var paths = new ArrayList<Path>();
        for (String outcome : List.of("ROLLBACK", "APPEND_FAULT", "COMMIT")) {
            Path file = evidence.resolve("jpa-mp-" + outcome.toLowerCase(java.util.Locale.ROOT).replace('_', '-') + ".json");
            var proof = mapper.readTree(UnifiedIdentityLiveClient.protectedBytes(file, evidence, 128 * 1024));
            if (!outcome.equals(proof.path("outcome").asText()) || Files.getLastModifiedTime(file, java.nio.file.LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(attemptStartedAt)) {
                throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
            }
            proofs.add(proof); paths.add(file);
        }
        validateJpaMpTransactionProofs(proofs, runId, digest, oid, attemptStartedAt, Instant.now());
        return List.copyOf(paths);
    }

    static void validateJpaMpTransactionProofs(List<JsonNode> proofs, String runId, String digest, long oid, Instant attemptStartedAt, Instant now) {
        if (proofs == null || proofs.size() != 3 || oid <= 0 || attemptStartedAt == null || now == null || attemptStartedAt.isAfter(now)) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
        var outcomes = new java.util.HashSet<String>(); var transactions = new java.util.HashSet<Long>();
        var expected = java.util.Set.of("schemaVersion", "runId", "manifestSha256", "databaseOid", "scope", "outcome", "status", "backendPid", "transactionId",
                "sameConnectionConfirmed", "baseline", "observed", "restored", "attemptedInsertCount", "restoreInsertCount", "committedOutboxDelta", "restoreOutboxDelta",
                "restorePerformed", "originalGrantsRestored", "startedAt", "recordedAt");
        for (var proof : proofs) {
            if (proof == null || !proof.isObject()) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
            var fields = new java.util.HashSet<String>(); proof.fieldNames().forEachRemaining(fields::add);
            String outcome = proof.path("outcome").asText();
            if (!expected.equals(fields) || transactionProofLong(proof, "schemaVersion") != 1L || !runId.equals(proof.path("runId").asText())
                    || !digest.equals(proof.path("manifestSha256").asText()) || transactionProofLong(proof, "databaseOid") != oid
                    || !"JPA_MP_SAME_TRANSACTION_ONLY".equals(proof.path("scope").asText()) || !"PASSED".equals(proof.path("status").asText())
                    || !java.util.Set.of("ROLLBACK", "APPEND_FAULT", "COMMIT").contains(outcome) || !outcomes.add(outcome)
                    || transactionProofLong(proof, "backendPid") == 0 || transactionProofLong(proof, "backendPid") > Integer.MAX_VALUE
                    || transactionProofLong(proof, "transactionId") == 0 || !transactions.add(transactionProofLong(proof, "transactionId"))
                    || transactionProofLong(proof, "attemptedInsertCount") != 1 || !proof.path("sameConnectionConfirmed").isBoolean() || !proof.path("sameConnectionConfirmed").booleanValue()
                    || !proof.path("originalGrantsRestored").isBoolean() || !proof.path("originalGrantsRestored").booleanValue() || !proof.path("restorePerformed").isBoolean()) {
                throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
            }
            Instant started = transactionProofInstant(proof, "startedAt"), recorded = transactionProofInstant(proof, "recordedAt");
            if (started.isBefore(attemptStartedAt) || recorded.isBefore(started) || recorded.isAfter(now)) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
            for (String name : List.of("baseline", "observed", "restored")) {
                var state = proof.path(name); var stateFields = new java.util.HashSet<String>(); state.fieldNames().forEachRemaining(stateFields::add);
                if (!state.isObject() || !stateFields.equals(java.util.Set.of("independentBackendPid", "roleVersion", "policyVersion", "authorizationStateVersion", "outboxCount", "rowsSha256"))
                        || transactionProofLong(state, "independentBackendPid") == 0 || transactionProofLong(state, "independentBackendPid") > Integer.MAX_VALUE
                        || transactionProofLong(state, "independentBackendPid") == transactionProofLong(proof, "backendPid")
                        || !state.path("rowsSha256").isTextual() || !state.path("rowsSha256").textValue().matches("[0-9a-f]{64}")) {
                    throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
                }
                for (String value : List.of("roleVersion", "policyVersion", "authorizationStateVersion", "outboxCount")) { transactionProofLong(state, value); }
            }
            var before = proof.path("baseline"); var after = proof.path("observed"); var restored = proof.path("restored");
            long delta = transactionProofLong(proof, "committedOutboxDelta"), restoreDelta = transactionProofLong(proof, "restoreOutboxDelta");
            if (delta != transactionProofLong(after, "outboxCount") - transactionProofLong(before, "outboxCount")
                    || restoreDelta != transactionProofLong(restored, "outboxCount") - transactionProofLong(after, "outboxCount")) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
            if ("COMMIT".equals(outcome)) {
                if (!proof.path("restorePerformed").booleanValue() || transactionProofLong(proof, "restoreInsertCount") != 1 || delta != 1 || restoreDelta != 1) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                for (String version : List.of("roleVersion", "policyVersion", "authorizationStateVersion")) {
                    if (transactionProofLong(after, version) <= transactionProofLong(before, version) || transactionProofLong(restored, version) <= transactionProofLong(after, version)) {
                        throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
                    }
                }
            } else {
                if (proof.path("restorePerformed").booleanValue() || transactionProofLong(proof, "restoreInsertCount") != 0 || delta != 0 || restoreDelta != 0) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                for (String field : List.of("roleVersion", "policyVersion", "authorizationStateVersion", "outboxCount", "rowsSha256")) {
                    if (!before.path(field).equals(after.path(field)) || !after.path(field).equals(restored.path(field))) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                }
            }
        }
    }

    static long transactionProofLong(JsonNode document, String field) {
        var value = document.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0L) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        return value.longValue();
    }

    static Instant transactionProofInstant(JsonNode document, String field) {
        var value = document.path(field);
        if (!value.isTextual() || !value.textValue().endsWith("Z")) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        try { return Instant.parse(value.textValue()); }
        catch (java.time.format.DateTimeParseException invalid) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED", invalid); }
    }

    @Test
    void jpaMpEvidenceCannotReplaceAnOutcomeOrHideRestoreVersionAndEventChanges() {
        String runId = UUID.randomUUID().toString(), digest = "a".repeat(64); Instant now = Instant.parse("2026-10-15T00:00:00Z");
        var proofs = transactionProofDocuments(runId, digest, now);
        assertThatCode(() -> validateJpaMpTransactionProofs(proofs, runId, digest, 71L, now.minusSeconds(5), now)).doesNotThrowAnyException();
        for (String fault : List.of("duplicate", "foreignRun", "sameBackend", "restoreVersion", "restoreEvent", "oldTime", "offsetTime", "overflow", "unknownScope")) {
            List<JsonNode> changed = proofs.stream().map(value -> (JsonNode) value.deepCopy()).toList();
            var last = (com.fasterxml.jackson.databind.node.ObjectNode) changed.get(2);
            if (fault.equals("duplicate")) { last.put("outcome", "ROLLBACK"); }
            else if (fault.equals("foreignRun")) { last.put("runId", UUID.randomUUID().toString()); }
            else if (fault.equals("sameBackend")) { last.withObject("observed").put("independentBackendPid", 301); }
            else if (fault.equals("restoreVersion")) { last.withObject("restored").put("roleVersion", 2); }
            else if (fault.equals("restoreEvent")) { last.put("restoreOutboxDelta", 0); }
            else if (fault.equals("oldTime")) { last.put("startedAt", now.minusSeconds(10).toString()); }
            else if (fault.equals("offsetTime")) { last.put("recordedAt", "2026-10-15T08:00:00+08:00"); }
            else if (fault.equals("overflow")) { last.put("transactionId", new java.math.BigInteger("18446744073709551616")); }
            else { last.put("scope", "LIVE002_COMPLETE"); }
            assertThatThrownBy(() -> validateJpaMpTransactionProofs(changed, runId, digest, 71L, now.minusSeconds(5), now)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        }
        assertThatThrownBy(() -> validateJpaMpTransactionProofs(proofs.subList(0, 2), runId, digest, 71L, now.minusSeconds(5), now)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
    }

    private static List<JsonNode> transactionProofDocuments(String runId, String digest, Instant now) {
        var result = new ArrayList<JsonNode>(); int ordinal = 0;
        for (String outcome : List.of("ROLLBACK", "APPEND_FAULT", "COMMIT")) {
            var value = UnifiedIdentityLiveClient.strictJson().createObjectNode().put("schemaVersion", 1).put("runId", runId).put("manifestSha256", digest)
                    .put("databaseOid", 71).put("scope", "JPA_MP_SAME_TRANSACTION_ONLY").put("outcome", outcome).put("status", "PASSED")
                    .put("backendPid", 301).put("transactionId", 701 + ordinal++).put("sameConnectionConfirmed", true).put("attemptedInsertCount", 1)
                    .put("restoreInsertCount", outcome.equals("COMMIT") ? 1 : 0).put("committedOutboxDelta", outcome.equals("COMMIT") ? 1 : 0)
                    .put("restoreOutboxDelta", outcome.equals("COMMIT") ? 1 : 0).put("restorePerformed", outcome.equals("COMMIT")).put("originalGrantsRestored", true)
                    .put("startedAt", now.minusSeconds(1).toString()).put("recordedAt", now.toString());
            for (int index = 0; index < 3; index++) {
                int offset = outcome.equals("COMMIT") ? index : 0;
                value.putObject(List.of("baseline", "observed", "restored").get(index)).put("independentBackendPid", 302 + index)
                        .put("roleVersion", 2 + offset).put("policyVersion", 7 + offset).put("authorizationStateVersion", 5 + offset)
                        .put("outboxCount", 4 + offset).put("rowsSha256", String.valueOf((char) ('b' + offset)).repeat(64));
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    record NativeSqlRowVO(@Positive long id, @Positive long tenantId, @NotBlank String externalId,
                          @NotBlank String name, @NotBlank String email, @NotBlank String status) { }

    static void requireOwnedRows(List<NativeSqlRowVO> rows, long tenantA, long tenantB,
                                 long idA, long idB, String nameA, String nameB) {
        if (rows == null || rows.size() != 2 || tenantA <= 0 || tenantB <= 0 || tenantA == tenantB
                || idA <= 0 || idB <= 0 || idA == idB || rows.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("XINGYUAN_VERIFICATION_FAILED");
        }
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (var row : rows) {
                if (!factory.getValidator().validate(row).isEmpty()) { throw new IllegalArgumentException("XINGYUAN_VERIFICATION_FAILED"); }
                boolean first = row.id() == idA && row.tenantId() == tenantA && row.name().equals(nameA);
                boolean second = row.id() == idB && row.tenantId() == tenantB && row.name().equals(nameB);
                if (!"ext-1".equals(row.externalId()) || !first && !second) {
                    throw new IllegalArgumentException("XINGYUAN_VERIFICATION_FAILED");
                }
            }
        }
        if (rows.stream().map(NativeSqlRowVO::id).distinct().count() != 2) {
            throw new IllegalArgumentException("XINGYUAN_VERIFICATION_FAILED");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "XINGYUAN_ISOLATED_INTEGRATION", matches = "true")
    void nativeLightSqlIsolationMatchesTheSameFormalTenantCalls() throws Exception {
        var client = UnifiedIdentityLiveClient.isolated();
        var mapper = UnifiedIdentityLiveClient.strictJson();
        Path manifestFile = Path.of(System.getProperty("xingyuan.isolated.manifest-file", System.getenv("XINGYUAN_INTEGRATION_MANIFEST")));
        byte[] manifestBytes = UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024);
        var manifest = mapper.readTree(manifestBytes);
        String digest = UnifiedIdentityLiveClient.sha256(manifestBytes);
        Path root = Path.of(manifest.path("runtimeRoot").asText());
        Path evidence = Path.of(System.getProperty("xingyuan.isolated.evidence-directory", root.resolve("evidence").toString()));
        requireEvidenceDirectory(root, evidence);
        if (Files.exists(evidence.resolve("native-tenant-isolation.json"))) { throw new IllegalStateException("XINGYUAN_STATE_CONFLICT"); }
        requireRunningProcesses(root, manifestFile, manifest, digest, evidence, java.util.Set.of("native-light"));
        var profile = verifiedProfile(root, manifest, digest, "native-light");
        // ext-1 is the original local adapter's only external identity; never replace it with a fake upstream.
        boolean localExternalUser = "false".equals(profile.getProperty("app.integrations.external-http.enabled", "false"));
        profile.clear();
        if (!localExternalUser) {
            throw new IllegalArgumentException("XINGYUAN_INPUT_UNAVAILABLE");
        }
        String resource = client.resourceForProcess("native-light");
        var first = client.formal("default", resource, "admin");
        var second = client.formal("tenant-b", resource, "admin");
        var viewer = client.formal("default", resource, "viewer");
        client.verifiedClaims(first); client.verifiedClaims(second); client.verifiedClaims(viewer);
        long tenantA = Long.parseLong(first.getTenantId());
        long tenantB = Long.parseLong(second.getTenantId());
        if (tenantA == tenantB || !first.getResourceUri().equals(second.getResourceUri())) {
            throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
        }
        var probe = new NativeResourceHttpProbe("native-light", root, manifestFile, digest,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(manifest.path("deadlines").path("httpSeconds").intValue())).build(),
                Duration.ofSeconds(manifest.path("deadlines").path("httpSeconds").intValue()));
        var receipt = mapper.readTree(UnifiedIdentityLiveClient.protectedBytes(root.resolve("evidence/db-light.json"), root.resolve("evidence"), 128 * 1024));
        requireLightReceipt(manifest, digest, receipt);
        try (var connection = ownedLightConnection(manifest, receipt)) {
            String table = userTable(connection);
            if (!readRows(connection, table, tenantA, tenantB).isEmpty()) { throw new IllegalStateException("XINGYUAN_STATE_CONFLICT"); }
            String nameA = "xingyuan-it-" + manifest.path("runId").asText() + "-a";
            String nameB = "xingyuan-it-" + manifest.path("runId").asText() + "-b";
            var bodyA = mapper.createObjectNode().put("externalId", "ext-1").put("name", nameA).put("email", nameA + "@xingyuan.invalid");
            var bodyB = mapper.createObjectNode().put("externalId", "ext-1").put("name", nameB).put("email", nameB + "@xingyuan.invalid");
            verifyLightConnection(connection, receipt);
            var createdA = probe.request("POST", "/api/users", first, bodyA, null, 200);
            verifyLightConnection(connection, receipt);
            var createdB = probe.request("POST", "/api/users", second, bodyB, null, 200);
            long idA = responseId(createdA), idB = responseId(createdB);
            var rows = readRows(connection, table, tenantA, tenantB);
            requireOwnedRows(rows, tenantA, tenantB, idA, idB, nameA, nameB);
            requireResponseMatchesSql(createdA, rows.stream().filter(row -> row.id() == idA).findFirst().orElseThrow());
            requireResponseMatchesSql(createdB, rows.stream().filter(row -> row.id() == idB).findFirst().orElseThrow());
            var readA = probe.request("GET", "/api/users/" + idA, first, null, null, 200);
            var readB = probe.request("GET", "/api/users/" + idB, second, null, null, 200);
            assertThat(readA).isEqualTo(createdA); assertThat(readB).isEqualTo(createdB);
            var forged = probe.request("GET", "/api/users/" + idA, first, null, second.getTenantId(), 200);
            assertThat(forged).isEqualTo(readA);
            var foreign = probe.request("GET", "/api/users/" + idA, second, null, null, 400);
            assertThat(foreign.path("success").isBoolean() && !foreign.path("success").booleanValue()).isTrue();
            assertThat(foreign.path("status").asText()).isEqualTo("USER_NOT_FOUND");
            probe.request("GET", "/api/users/" + idA, null, null, null, 401);
            verifyLightConnection(connection, receipt);
            probe.request("POST", "/api/users", viewer, bodyA, null, 403);
            assertThat(probe.request("GET", "/api/users/" + idA, first, null, null, 200)).isEqualTo(readA);
            assertThat(readRows(connection, table, tenantA, tenantB)).isEqualTo(rows);
            var proof = mapper.createObjectNode().put("schemaVersion", 1).put("runId", manifest.path("runId").asText())
                    .put("manifestSha256", digest).put("status", "PASSED").put("resourceUri", first.getResourceUri().toASCIIString())
                    .put("tenantA", first.getTenantId()).put("tenantB", second.getTenantId())
                    .put("tenantARowId", Long.toString(idA)).put("tenantBRowId", Long.toString(idB))
                    .put("defaultRowsHash", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(readA)))
                    .put("tenantBRowsHash", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(readB)))
                    .put("forgedHeaderRowsHash", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(forged)))
                    .put("sqlRowsSha256", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(rows)))
                    .put("tokenASha256", first.getTokenSha256()).put("tokenBSha256", second.getTokenSha256())
                    .put("sqlIsolationConfirmed", true).put("observedAt", Instant.now().toString());
            client.publishNativeTenantIsolationProof(evidence, proof);
        }
    }

    static void requireLightReceipt(JsonNode manifest, String digest, JsonNode receipt) {
        if (!manifest.path("runId").asText().equals(receipt.path("runId").asText())
                || !digest.equals(receipt.path("manifestSha256").asText()) || !"light".equals(receipt.path("role").asText())
                || !"xingyuan_it_light_20261011".equals(receipt.path("databaseName").asText())
                || !receipt.path("oid").isIntegralNumber() || !receipt.path("oid").canConvertToLong() || receipt.path("oid").longValue() <= 0
                || !receipt.path("databaseOwner").asText().matches("[A-Za-z_][A-Za-z0-9_-]{0,62}")
                || !("xingyuan-it:" + manifest.path("runId").asText() + ":" + digest).equals(receipt.path("ownerComment").asText())) {
            throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
        }
    }

    static Connection ownedLightConnection(JsonNode manifest, JsonNode receipt) throws Exception {
        byte[] bytes = UnifiedIdentityLiveClient.protectedBytes(Path.of(manifest.path("postgres").path("credentialFile").asText()), null, 4096);
        var credentials = new Properties();
        credentials.setProperty("user", receipt.path("databaseOwner").asText());
        try {
            String password = new String(bytes, StandardCharsets.UTF_8);
            if (password.endsWith("\r\n")) { password = password.substring(0, password.length() - 2); }
            else if (password.endsWith("\n")) { password = password.substring(0, password.length() - 1); }
            if (password.isEmpty()) { throw new IllegalArgumentException("XINGYUAN_INPUT_INVALID"); }
            credentials.setProperty("password", password);
            var connection = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/xingyuan_it_light_20261011?connectTimeout=5&socketTimeout=30", credentials);
            try {
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                connection.setAutoCommit(false);
                connection.setReadOnly(true);
                verifyLightConnection(connection, receipt);
                return connection;
            } catch (java.sql.SQLException | RuntimeException failure) {
                try { connection.close(); } catch (java.sql.SQLException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        } finally { java.util.Arrays.fill(bytes, (byte) 0); credentials.clear(); }
    }

    static void verifyLightConnection(Connection connection, JsonNode receipt) throws java.sql.SQLException {
        try (var query = connection.prepareStatement("""
                SELECT current_database(), host(inet_server_addr()), inet_server_port(), d.oid::bigint,
                       shobj_description(d.oid,'pg_database'), pg_get_userbyid(d.datdba),
                       current_setting('transaction_read_only'), current_setting('transaction_isolation')
                FROM pg_database d WHERE d.datname=current_database()
                """)) {
            query.setQueryTimeout(5);
            try (var result = query.executeQuery()) {
                if (!result.next() || !"xingyuan_it_light_20261011".equals(result.getString(1)) || !"127.0.0.1".equals(result.getString(2))
                        || result.getInt(3) != 5432 || result.getLong(4) != receipt.path("oid").longValue()
                        || !receipt.path("ownerComment").asText().equals(result.getString(5))
                        || !receipt.path("databaseOwner").asText().equals(result.getString(6))
                        || !"on".equals(result.getString(7)) || !"read committed".equals(result.getString(8)) || result.next()) {
                    throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
                }
            }
        }
    }

    static String userTable(Connection connection) throws java.sql.SQLException {
        try (var query = connection.prepareStatement("SELECT n.nspname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relname='users' AND c.relkind='r' AND n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema'")) {
            query.setQueryTimeout(5);
            try (var result = query.executeQuery()) {
                if (!result.next()) { throw new IllegalStateException("XINGYUAN_INPUT_UNAVAILABLE"); }
                String schema = result.getString(1);
                if (!schema.matches("[a-z][a-z0-9_]{0,62}") || result.next()) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
                return '"' + schema + "\".\"users\"";
            }
        }
    }

    static List<NativeSqlRowVO> readRows(Connection connection, String table, long tenantA, long tenantB) throws java.sql.SQLException {
        if (!table.matches("\"[a-z][a-z0-9_]{0,62}\"\\.\"users\"")) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        try (var query = connection.prepareStatement("SELECT id,tenant_id,external_id,name,email,status FROM " + table + " WHERE tenant_id IN (?,?) AND external_id=? AND deleted_at IS NULL ORDER BY id LIMIT 3")) {
            query.setQueryTimeout(5); query.setLong(1, tenantA); query.setLong(2, tenantB); query.setString(3, "ext-1");
            try (var result = query.executeQuery()) {
                var rows = new ArrayList<NativeSqlRowVO>();
                while (result.next()) { rows.add(new NativeSqlRowVO(result.getLong(1), result.getLong(2), result.getString(3), result.getString(4), result.getString(5), result.getString(6))); }
                return List.copyOf(rows);
            }
        }
    }

    static long responseId(JsonNode value) {
        if (!value.path("id").isTextual() || !value.path("id").asText().matches("[1-9][0-9]{0,18}")) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        return Long.parseLong(value.path("id").asText());
    }

    static void requireResponseMatchesSql(JsonNode response, NativeSqlRowVO row) {
        assertThat(responseId(response)).isEqualTo(row.id());
        assertThat(response.path("name").asText()).isEqualTo(row.name());
        assertThat(response.path("email").asText()).isEqualTo(row.email());
        assertThat(response.path("status").asText()).isEqualTo(row.status());
    }

    static void requireEvidenceDirectory(Path root, Path evidence) throws Exception {
        if (!evidence.equals(root.resolve("evidence")) && (!root.resolve("evidence").equals(evidence.getParent())
                || !evidence.getFileName().toString().matches("verify-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        for (Path directory = evidence; directory != null && directory.startsWith(root); directory = directory.getParent()) {
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory) || !Files.getOwner(root).equals(Files.getOwner(directory))
                    || !Files.getPosixFilePermissions(directory).equals(PosixFilePermissions.fromString("rwx------"))) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        }
    }

    static Properties verifiedProfile(Path root, JsonNode manifest, String digest, String role) throws Exception {
        if (!java.util.Set.of("native-light", "native-web", "native-service", "native-agent", "yuheng").contains(role)) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        var ready = UnifiedIdentityLiveClient.strictJson().readTree(UnifiedIdentityLiveClient.protectedBytes(root.resolve("evidence/profiles-ready.json"), root.resolve("evidence"), 128 * 1024));
        Path profile = root.resolve("profiles/" + role + ".properties");
        byte[] bytes = UnifiedIdentityLiveClient.protectedBytes(profile, root.resolve("profiles"), 2 * 1024 * 1024);
        if (!manifest.path("runId").asText().equals(ready.path("runId").asText()) || !digest.equals(ready.path("manifestSha256").asText())
                || !ready.path("schemaVersion").isIntegralNumber() || !ready.path("schemaVersion").canConvertToInt() || ready.path("schemaVersion").intValue() != 1
                || !profile.toString().equals(ready.path("profiles").path(role).path("path").asText())
                || !UnifiedIdentityLiveClient.sha256(bytes).equals(ready.path("profiles").path(role).path("sha256").asText())) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        var properties = new Properties();
        try { properties.load(new java.io.StringReader(new String(bytes, StandardCharsets.UTF_8))); }
        finally { java.util.Arrays.fill(bytes, (byte) 0); }
        return properties;
    }

    static void requireRunningProcesses(Path root, Path manifestFile, JsonNode manifest, String digest, Path evidence, java.util.Set<String> expected) throws Exception {
        if (expected.isEmpty() || !java.util.Set.of("shoubing", "jianshen", "tianshu", "yuheng", "mcp", "llm", "native-light", "native-web", "native-service", "native-agent").containsAll(expected)) {
            throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
        }
        Path output = evidence.resolve("native-sql-status-" + UUID.randomUUID() + ".json");
        var command = new ProcessBuilder("python3", "-I", Path.of(manifest.path("repoRoot").asText()).resolve("scripts/unified-xingyuan/lib/isolated_integration.py").toString(),
                "process-state", "status", manifestFile.toString(), output.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!command.waitFor(manifest.path("deadlines").path("operationSeconds").intValue(), TimeUnit.SECONDS) || command.exitValue() != 0) { throw new IllegalStateException("XINGYUAN_DEPENDENCY_UNAVAILABLE"); }
        } finally { if (command.isAlive()) { command.destroy(); command.waitFor(5, TimeUnit.SECONDS); } }
        var observed = UnifiedIdentityLiveClient.strictJson().readTree(UnifiedIdentityLiveClient.protectedBytes(output, root.resolve("evidence"), 128 * 1024));
        if (!manifest.path("runId").asText().equals(observed.path("runId").asText()) || !digest.equals(observed.path("manifestSha256").asText())
                || !"CLI-008".equals(observed.path("operationId").asText()) || !observed.path("targets").isArray()) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        for (String role : expected) {
            var targets = java.util.stream.StreamSupport.stream(observed.path("targets").spliterator(), false)
                    .filter(value -> role.equals(value.path("targetKey").asText())).toList();
            if (targets.size() != 1 || !"RUNNING".equals(targets.getFirst().path("state").asText())) { throw new IllegalStateException("XINGYUAN_DEPENDENCY_UNAVAILABLE"); }
        }
    }

    @RequiredArgsConstructor
    @Slf4j
    static final class NativeResourceHttpProbe {
        private final String role;
        private final Path root;
        private final Path manifestFile;
        private final String digest;
        private final HttpClient http;
        private final Duration timeout;

        JsonNode observedGraphQl(FormalReferenceDTO reference, JsonNode body, String tenantHeader, int callbacks, int matched,
                com.fasterxml.jackson.databind.node.ArrayNode observations) throws Exception {
            if (!java.util.Set.of("native-light", "native-web").contains(role) || reference == null) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
            var mapper = UnifiedIdentityLiveClient.strictJson(); byte[] raw = UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024);
            if (!digest.equals(UnifiedIdentityLiveClient.sha256(raw))) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
            var manifest = mapper.readTree(raw); Path evidence = root.resolve("evidence"); requireEvidenceDirectory(root, evidence);
            var before = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, role, reference.getRealmCode(), "GRAPHQL_CONTEXT_COUNTS", null);
            var actual = request("POST", "/graphql", reference, body, tenantHeader, 200);
            var after = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, role, reference.getRealmCode(), "GRAPHQL_CONTEXT_COUNTS", null);
            requireGraphQlAsyncDelta(before, after, callbacks, matched);
            var item = observations.addObject().put("realmCode", reference.getRealmCode()).put("responseSha256", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(actual)))
                    .put("callbacks", callbacks).put("matched", matched);
            item.set("before", before); item.set("after", after); return actual;
        }

        JsonNode request(String method, String path, FormalReferenceDTO reference, JsonNode body, String tenantHeader, int expected) throws Exception {
            return request(method, path, reference, body, tenantHeader, expected, null);
        }

        JsonNode request(String method, String path, FormalReferenceDTO reference, JsonNode body, String tenantHeader, int expected,
                java.util.function.Consumer<HttpAuditObservationVO> observations) throws Exception {
            byte[] manifestBytes = UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024);
            if (!digest.equals(UnifiedIdentityLiveClient.sha256(manifestBytes))) {
                throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
            }
            JsonNode manifest = UnifiedIdentityLiveClient.strictJson().readTree(manifestBytes);
            var builder = HttpRequest.newBuilder(nativeResourceEndpoint(manifest, role, method, path)).timeout(timeout)
                    .header("X-Request-Id", UUID.randomUUID().toString());
            String trace = UUID.randomUUID().toString().replace("-", ""); Instant started = Instant.now();
            if (observations != null) { builder.header("traceparent", "00-" + trace + '-' + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "-01"); }
            if (reference != null) {
                byte[] bytes = UnifiedIdentityLiveClient.protectedBytes(reference.getTokenFile(), root.resolve("secrets"), 128 * 1024);
                try {
                    if (!reference.getExpiresAt().isAfter(Instant.now()) || !reference.getTokenSha256().equals(UnifiedIdentityLiveClient.sha256(bytes))) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
                    builder.header("Authorization", "Bearer " + new String(bytes, StandardCharsets.UTF_8));
                } finally { java.util.Arrays.fill(bytes, (byte) 0); }
            }
            if (tenantHeader != null) { builder.header("X-Tenant-Id", tenantHeader); }
            if (method.equals("POST")) { builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())); }
            else if (method.equals("PUT")) { builder.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body.toString())); }
            else { builder.GET(); }
            var captured = new ByteArrayOutputStream();
            var response = http.send(builder.build(), boundedBody(captured));
            assertThat(response.statusCode()).isEqualTo(expected);
            if (observations != null) { observations.accept(observedHttpCall(response, trace, started)); }
            return UnifiedIdentityLiveClient.strictJson().readTree(captured.toByteArray());
        }
    }

    static void requireGraphQlAsyncDelta(JsonNode before, JsonNode after, long callbacks, long matched) {
        if (callbacks < 1 || callbacks > 2 || matched < 0 || matched > callbacks) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        for (var value : List.of(before, after)) {
            var fields = new java.util.HashSet<String>(); value.path("counts").fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(java.util.Set.of("differentThread", "matchedContext", "finallyRestored", "failure"))) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
            for (String key : fields) { transactionProofLong(value.path("counts"), key); }
            if (transactionProofLong(value.path("counts"), "failure") != 0
                    || transactionProofLong(value.path("counts"), "matchedContext") > transactionProofLong(value.path("counts"), "differentThread")
                    || transactionProofLong(value.path("counts"), "finallyRestored") != transactionProofLong(value.path("counts"), "differentThread")) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        }
        for (String key : List.of("differentThread", "matchedContext", "finallyRestored")) {
            long expected = key.equals("matchedContext") ? matched : callbacks;
            if (Math.subtractExact(transactionProofLong(after.path("counts"), key), transactionProofLong(before.path("counts"), key)) != expected) {
                throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
            }
        }
    }

    static void requireGraphQlAsyncEvidence(JsonNode value, String role) {
        if (!java.util.Set.of("native-light", "native-web").contains(role) || !value.path("asyncObservations").isArray()
                || value.path("asyncObservations").size() != 3) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        var seen = new java.util.HashSet<String>();
        for (var observation : value.path("asyncObservations")) {
            String realm = observation.path("realmCode").asText(); long matched = transactionProofLong(observation, "matched");
            long callbacks = transactionProofLong(observation, "callbacks");
            if (!java.util.Set.of("default", "tenant-b").contains(realm) || !seen.add(realm + ':' + matched)
                    || !observation.path("responseSha256").asText().matches("[0-9a-f]{64}")
                    || matched != 0 && matched != 2 || callbacks != (matched == 0 && role.equals("native-web") ? 1 : 2)) {
                throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
            }
            requireGraphQlAsyncDelta(observation.path("before"), observation.path("after"), callbacks, matched);
        }
        if (!seen.equals(java.util.Set.of("default:2", "tenant-b:2", "default:0"))) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
    }

    @Test
    void graphQlAsyncProofRequiresActualWorkerMatchedScopeAndFinallyCounts() throws Exception {
        var mapper = UnifiedIdentityLiveClient.strictJson();
        var before = mapper.readTree("{\"counts\":{\"differentThread\":10,\"matchedContext\":7,\"finallyRestored\":10,\"failure\":0}}");
        var after = mapper.readTree("{\"counts\":{\"differentThread\":12,\"matchedContext\":9,\"finallyRestored\":12,\"failure\":0}}");
        assertThatCode(() -> requireGraphQlAsyncDelta(before, after, 2, 2)).doesNotThrowAnyException();
        for (String key : List.of("differentThread", "matchedContext", "finallyRestored")) {
            var invalid = after.deepCopy(); invalid.withObject("counts").put(key, before.path("counts").path(key).longValue());
            assertThatThrownBy(() -> requireGraphQlAsyncDelta(before, invalid, 2, 2)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        }
        var leaked = after.deepCopy(); leaked.withObject("counts").put("failure", 1);
        assertThatThrownBy(() -> requireGraphQlAsyncDelta(before, leaked, 2, 2)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        var sensitive = after.deepCopy(); sensitive.withObject("counts").put("subject", "forbidden");
        assertThatThrownBy(() -> requireGraphQlAsyncDelta(before, sensitive, 2, 2)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        var denied = after.deepCopy(); denied.withObject("counts").put("matchedContext", 7);
        assertThatCode(() -> requireGraphQlAsyncDelta(before, denied, 2, 0)).doesNotThrowAnyException();
    }

    private static JsonNode testOrigins() {
        var manifest = UnifiedIdentityLiveClient.strictJson().createObjectNode();
        var origins = manifest.putObject("ciliumOrigins");
        for (String role : List.of("llm", "native-web", "native-light", "native-agent")) {
            origins.put(role, "https://" + role + ".apps.example.test");
        }
        return manifest;
    }

    static URI nativeResourceEndpoint(JsonNode manifest, String role, String method, String path) {
        URI origin = UnifiedIdentityLiveClient.ciliumOrigin(manifest, role);
        if ("yuheng".equals(role) && knowledgeManagementPath(method, path)) { return origin.resolve(path); }
        if ("llm".equals(role)) {
            if (!("GET".equals(method) && "/v1/models".equals(path) || "POST".equals(method)
                    && java.util.Set.of("/v1/chat/completions", "/v1/embeddings", "/v1/responses", "/v1/messages").contains(path))) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
            return origin.resolve(path);
        }
        if (PLATFORM_READ_PATHS.containsKey(role) && "GET".equals(method) && PLATFORM_READ_PATHS.get(role).equals(path)) {
            return origin.resolve(path);
        }
        if ("native-agent".equals(role)) {
            String prefix = "/api/v1/knowledge-bases";
            if (!("POST".equals(method) && prefix.equals(path) || "GET".equals(method)
                    && (path.matches(prefix + "/[1-9][0-9]{0,18}") || path.matches(prefix + "\\?page=1&size=100&keyword=[A-Za-z0-9._-]{2,64}")))) {
                throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
            }
            return origin.resolve(path);
        }
        String prefix;
        if ("native-light".equals(role)) { prefix = "/api/users"; }
        else if ("native-web".equals(role)) { prefix = "/api/v1/users"; }
        else { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        if (!("POST".equals(method) && (prefix.equals(path) || "/graphql".equals(path)) || "GET".equals(method) && path.matches(prefix + "/[1-9][0-9]{0,18}"))) {
            throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
        }
        return origin.resolve(path);
    }

    static boolean knowledgeManagementPath(String method, String path) {
        if (path == null) { return false; }
        String base = "/api/v1/yuheng/admin/knowledge-bases";
        return method.equals("GET") && (path.equals(base + "?page=1&size=100") || path.matches(base + "/[1-9][0-9]{0,18}") || path.equals("/api/v1/yuheng/admin/llm/models"))
                || method.equals("POST") && path.equals(base);
    }

    static void requireLlmSecurityEnvelope(JsonNode response, String path, int status) {
        String code = status == 401 ? "authentication_error" : "permission_denied", type = status == 401 ? "authentication_error" : "permission_error";
        if (!response.isObject() || !response.path("error").isObject() || !type.equals(response.path("error").path("type").asText())
                || !response.path("error").path("message").isTextual() || response.path("error").path("message").asText().isBlank()
                || !path.equals("/v1/messages") && !code.equals(response.path("error").path("code").asText())
                || path.equals("/v1/messages") && !"error".equals(response.path("type").asText())) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "XINGYUAN_ISOLATED_INTEGRATION", matches = "true")
    void formalPublicFamilyRevocationRejectsTheSameUnexpiredTokenBeforeBusiness() throws Exception {
        var client = UnifiedIdentityLiveClient.isolated(); var mapper = UnifiedIdentityLiveClient.strictJson();
        Path manifestFile = Path.of(System.getProperty("xingyuan.isolated.manifest-file", System.getenv("XINGYUAN_INTEGRATION_MANIFEST")));
        byte[] raw = UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024); var manifest = mapper.readTree(raw);
        String digest = UnifiedIdentityLiveClient.sha256(raw); Path root = Path.of(manifest.path("runtimeRoot").asText());
        Path evidence = Path.of(System.getProperty("xingyuan.isolated.evidence-directory", root.resolve("evidence").toString())); requireEvidenceDirectory(root, evidence);
        var first = client.formal("default", client.resourceForProcess("native-light"), "admin"); var second = client.formal("tenant-b", first.getResourceKey(), "admin");
        client.bindNativeTenantReadEndpoints(first, second); var verified = client.verifiedClaims(first);
        if (!first.getExpiresAt().isAfter(Instant.now().plusSeconds(30))) { throw new IllegalStateException("XINGYUAN_INPUT_EXPIRED"); }
        var before = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "native-light", "default", "BUSINESS_COUNTS", null);
        if (client.read(first) != 200) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        var successful = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "native-light", "default", "BUSINESS_COUNTS", null);
        UnifiedIdentityLiveClient.requireOnlyBusinessMethodDelta(before, successful, "get", 1);
        client.awaitFormalRevocation(first, verified);
        var deniedBefore = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "native-light", "default", "BUSINESS_COUNTS", null);
        if (!first.getExpiresAt().isAfter(Instant.now()) || client.read(first) != 401 || !first.getExpiresAt().isAfter(Instant.now())) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        var deniedAfter = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "native-light", "default", "BUSINESS_COUNTS", null);
        if (!deniedBefore.equals(deniedAfter)) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        Path receipt = evidence.resolve("formal-revocation-" + first.getTokenSha256() + ".json");
        var proof = mapper.createObjectNode().put("schemaVersion", 1).put("runId", manifest.path("runId").asText()).put("manifestSha256", digest)
                .put("scope", "ORIGINAL_PUBLIC_CLI_FAMILY_REVOKE_AND_ZERO_BUSINESS_ONLY").put("status", "PASSED").put("tokenReference", first.getTokenSha256())
                .put("tokenExpiresAt", first.getExpiresAt().toString()).put("beforeStatus", 200).put("afterStatus", 401)
                .put("ownerReceipt", root.relativize(receipt).toString()).put("ownerReceiptSha256", UnifiedIdentityLiveClient.sha256(UnifiedIdentityLiveClient.protectedBytes(receipt, evidence, 128 * 1024)))
                .put("recordedAt", Instant.now().toString());
        proof.set("before", before); proof.set("successful", successful); proof.set("deniedBefore", deniedBefore); proof.set("deniedAfter", deniedAfter);
        XingyuanNativeAuthenticationIT.publishNewNativeEvidence(root, evidence.resolve("resource-formal-family-revocation.json"), proof);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "XINGYUAN_ISOLATED_INTEGRATION", matches = "true")
    void actualLlmProtocolsRejectMissingAudienceAndScopeBeforeInference() throws Exception {
        var client = UnifiedIdentityLiveClient.isolated(); var mapper = UnifiedIdentityLiveClient.strictJson();
        Path manifestFile = Path.of(System.getProperty("xingyuan.isolated.manifest-file", System.getenv("XINGYUAN_INTEGRATION_MANIFEST")));
        byte[] bytes = UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024); var manifest = mapper.readTree(bytes);
        Path root = Path.of(manifest.path("runtimeRoot").asText()); String digest = UnifiedIdentityLiveClient.sha256(bytes);
        Path evidence = Path.of(System.getProperty("xingyuan.isolated.evidence-directory", root.resolve("evidence").toString())); requireEvidenceDirectory(root, evidence);
        requireRunningProcesses(root, manifestFile, manifest, digest, evidence, java.util.Set.of("llm", "shoubing"));
        Duration timeout = Duration.ofSeconds(manifest.path("deadlines").path("httpSeconds").intValue());
        var proof = mapper.createObjectNode().put("schemaVersion", 1).put("runId", manifest.path("runId").asText()).put("manifestSha256", digest)
                .put("scope", "ACTUAL_LLM_PROTOCOL_IDENTITY_SCOPE_GUARDS_ONLY"); var observations = proof.putArray("observations");
        try (var http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var api = new NativeResourceHttpProbe("llm", root, manifestFile, digest, http, timeout);
            for (String realm : List.of("default", "tenant-b")) {
                var full = client.formal(realm, client.resourceForLlm(), "admin"); client.verifiedClaims(full);
                if (!full.getScopes().containsAll(java.util.Set.of("yuheng:llm:read", "yuheng:llm:invoke"))) { throw new IllegalArgumentException("XINGYUAN_INPUT_UNAVAILABLE"); }
                var foreign = client.formal(realm, client.resourceForAlias("edge-yuheng"), "admin"); client.verifiedClaims(foreign);
                var restricted = acquireIdentityOnlyProbe(client, root, evidence, manifestFile, manifest, digest, realm, full.getResourceKey());
                var before = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "llm", realm, "BUSINESS_COUNTS", null);
                var models = api.request("GET", "/v1/models", full, null, null, 200);
                if (!"list".equals(models.path("object").asText()) || !models.path("data").isArray()) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                var baseline = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "llm", realm, "BUSINESS_COUNTS", null);
                UnifiedIdentityLiveClient.requireOnlyBusinessMethodDelta(before, baseline, "listLlmModels", 1);
                var results = mapper.createArrayNode();
                for (String path : List.of("/v1/models", "/v1/chat/completions", "/v1/embeddings", "/v1/responses", "/v1/messages")) {
                    String method = path.equals("/v1/models") ? "GET" : "POST";
                    var body = mapper.createObjectNode().put("model", "scope-probe").put("stream", false);
                    requireLlmSecurityEnvelope(api.request(method, path, null, body, null, 401), path, 401);
                    requireLlmSecurityEnvelope(api.request(method, path, foreign, body, null, 401), path, 401);
                    requireLlmSecurityEnvelope(api.request(method, path, restricted, body, null, 403), path, 403);
                    results.addObject().put("path", path).put("bareStatus", 401).put("foreignAudienceStatus", 401).put("missingScopeStatus", 403);
                }
                var denied = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "llm", realm, "BUSINESS_COUNTS", null);
                if (!baseline.equals(denied)) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                api.request("GET", "/v1/models", full, null, null, 200);
                var recovered = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "llm", realm, "BUSINESS_COUNTS", null);
                UnifiedIdentityLiveClient.requireOnlyBusinessMethodDelta(denied, recovered, "listLlmModels", 1);
                var item = observations.addObject().put("realmCode", realm).put("tokenReference", full.getTokenSha256()).put("identityOnlyTokenReference", restricted.getTokenSha256())
                        .put("catalogStatus", 200).put("externalInferenceExecuted", false);
                item.set("before", before); item.set("afterCatalog", baseline); item.set("afterDenied", denied); item.set("recovered", recovered); item.set("protocols", results);
            }
        }
        proof.put("status", "PASSED").put("recordedAt", Instant.now().toString());
        XingyuanNativeAuthenticationIT.publishNewNativeEvidence(root, evidence.resolve("resource-llm-protocol-guards.json"), proof);
    }

    @Test
    void llmTargetsAndErrorsPreserveAllFiveOriginalProtocolPaths() {
        var mapper = UnifiedIdentityLiveClient.strictJson();
        assertThat(nativeResourceEndpoint(testOrigins(), "llm", "GET", "/v1/models").toASCIIString()).isEqualTo("https://llm.apps.example.test/v1/models");
        assertThatThrownBy(() -> nativeResourceEndpoint(testOrigins(), "llm", "POST", "/v1/models")).hasMessage("XINGYUAN_TARGET_UNSAFE");
        assertThatThrownBy(() -> nativeResourceEndpoint(testOrigins(), "llm", "GET", "https://foreign.invalid/v1/models")).hasMessage("XINGYUAN_TARGET_UNSAFE");
        var error = mapper.createObjectNode().put("type", "error"); error.putObject("error").put("type", "permission_error").put("message", "Denied").put("code", "permission_denied");
        assertThatCode(() -> requireLlmSecurityEnvelope(error, "/v1/messages", 403)).doesNotThrowAnyException();
        error.remove("type"); assertThatThrownBy(() -> requireLlmSecurityEnvelope(error, "/v1/messages", 403)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        assertThatCode(() -> requireLlmSecurityEnvelope(error, "/v1/chat/completions", 403)).doesNotThrowAnyException();
        error.withObject("error").put("type", "permission_denied");
        assertThatThrownBy(() -> requireLlmSecurityEnvelope(error, "/v1/chat/completions", 403)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
    }

    static TaskSpoolFault createTaskSpoolFault(Path root, Path directory, String kind, long maximumBytes) throws Exception {
        if (!root.isAbsolute() || !root.equals(root.normalize()) || !directory.equals(root.resolve("evidence/oauth-spool-yuheng"))
                || !java.util.Set.of("DEAD", "CAPACITY").contains(kind) || maximumBytes <= 0 || maximumBytes > 104857600) {
            throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
        }
        requirePrivateSpoolDirectory(root, directory);
        Object directoryKey = Files.readAttributes(directory, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey();
        Path path = directory.resolve(".xingyuan-task-fault-" + UUID.randomUUID() + (kind.equals("DEAD") ? ".dead" : ".capacity"));
        long size = kind.equals("DEAD") ? 1 : maximumBytes;
        try (var channel = java.nio.channels.FileChannel.open(path, java.util.Set.of(java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            channel.position(size - 1); channel.write(java.nio.ByteBuffer.wrap(new byte[] {0})); channel.force(true);
        }
        requirePrivateSpoolDirectory(root, directory);
        if (directoryKey == null || !directoryKey.equals(Files.readAttributes(directory, java.nio.file.attribute.BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey())) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.fileKey() == null || attributes.size() != size
                || !Files.getOwner(root).equals(Files.getOwner(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                || !Files.getPosixFilePermissions(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------"))) {
            throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
        }
        try (var parent = java.nio.channels.FileChannel.open(directory, java.nio.file.StandardOpenOption.READ)) { parent.force(true); }
        return new TaskSpoolFault(root, directoryKey, path, attributes.fileKey(), size, attributes.lastModifiedTime());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "XINGYUAN_ISOLATED_INTEGRATION", matches = "true")
    void actualYuhengSpoolFaultsFailClosedAndRecoverWithoutEnteringBusiness() throws Exception {
        var client = UnifiedIdentityLiveClient.isolated(); var mapper = UnifiedIdentityLiveClient.strictJson();
        Path manifestFile = Path.of(System.getProperty("xingyuan.isolated.manifest-file", System.getenv("XINGYUAN_INTEGRATION_MANIFEST")));
        byte[] manifestBytes = UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024); var manifest = mapper.readTree(manifestBytes);
        String digest = UnifiedIdentityLiveClient.sha256(manifestBytes); Path root = Path.of(manifest.path("runtimeRoot").asText());
        Path evidence = Path.of(System.getProperty("xingyuan.isolated.evidence-directory", root.resolve("evidence").toString()));
        requireEvidenceDirectory(root, evidence);
        var profile = verifiedProfile(root, manifest, digest, "yuheng"); var flat = new java.util.LinkedHashMap<String, Object>();
        profile.forEach((key, value) -> flat.put(key.toString(), value));
        var audit = new org.springframework.boot.context.properties.bind.Binder(new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(flat))
                .bind("egon.cola.platform.tianquan.shoubing.audit", top.egon.cola.platform.tianquan.shoubing.starter.audit.OAuthAuditProperties.class)
                .orElseThrow(() -> new IllegalArgumentException("XINGYUAN_INPUT_UNAVAILABLE"));
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            new top.egon.cola.component.common.core.validation.ValidationUtils(validators.getValidator()).validate(audit);
        }
        if (!root.resolve("evidence/oauth-spool-yuheng").equals(audit.spoolDirectory()) || audit.spoolMaxBytes() > 104857600
                || !Path.of(manifest.path("secretFiles").path("audit-hmac").asText()).equals(audit.hmacKeyFile())
                || !("xingyuan-it:" + manifest.path("runId").asText() + ":oauth:audit:v1").equals(audit.stream())
                || !"true".equals(profile.getProperty("egon.cola.platform.tianquan.shoubing.usage-observation.enabled"))) {
            throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
        }
        requirePrivateSpoolDirectory(root, audit.spoolDirectory());
        var reference = client.formal("default", client.resourceForAlias("edge-yuheng"), "admin"); client.verifiedClaims(reference);
        requireRunningProcesses(root, manifestFile, manifest, digest, evidence, java.util.Set.of("yuheng", "shoubing"));
        Duration timeout = Duration.ofSeconds(manifest.path("deadlines").path("httpSeconds").intValue());
        var proof = mapper.createObjectNode().put("schemaVersion", 1).put("runId", manifest.path("runId").asText()).put("manifestSha256", digest)
                .put("scope", "YUHENG_TASK_SPOOL_IO_READINESS_AND_RECOVERY").put("tokenReference", reference.getTokenSha256())
                .put("resourceUri", reference.getResourceUri().toASCIIString()); var observations = proof.putArray("observations");
        try (var http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var api = new NativeResourceHttpProbe("yuheng", root, manifestFile, digest, http, timeout);
            for (String kind : List.of("DEAD", "CAPACITY")) {
                awaitYuhengReadiness(http, yuhengManagementReadiness(manifest), timeout, true);
                var before = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", "default", "BUSINESS_COUNTS", null);
                api.request("GET", "/api/v1/yuheng/admin/scopes", reference, null, null, 200);
                var baseline = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", "default", "BUSINESS_COUNTS", null);
                if (Math.addExact(before.path("counts").path("list").longValue(), 1) != baseline.path("counts").path("list").longValue()) {
                    throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
                }
                // An owned IO marker exercises the actual receiver. Signed DEAD receipts have a separate original-frame test.
                try (var fault = createTaskSpoolFault(root, audit.spoolDirectory(), kind, audit.spoolMaxBytes())) {
                    XingyuanNativeAuthenticationIT.publishNewNativeEvidence(root, evidence.resolve("resource-spool-fault-" + UUID.randomUUID() + ".json"),
                            mapper.createObjectNode().put("schemaVersion", 1).put("runId", manifest.path("runId").asText()).put("manifestSha256", digest)
                                    .put("kind", kind).put("ownedFile", root.relativize(fault.path).toString()).put("logicalSize", fault.size).put("recordedAt", Instant.now().toString()));
                    awaitYuhengReadiness(http, yuhengManagementReadiness(manifest), timeout, false);
                    var denied = api.request("GET", "/api/v1/yuheng/admin/scopes", reference, null, null, 503);
                    if (!"OAUTH_AUDIT_DELIVERY_UNAVAILABLE".equals(denied.path("code").asText())) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                    var after = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", "default", "BUSINESS_COUNTS", null);
                    if (!baseline.equals(after)) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                    var observation = observations.addObject().put("kind", kind).put("readinessStatus", 503).put("businessStatus", 503);
                    observation.set("before", baseline); observation.set("after", after);
                }
                awaitYuhengReadiness(http, yuhengManagementReadiness(manifest), timeout, true);
                api.request("GET", "/api/v1/yuheng/admin/scopes", reference, null, null, 200);
                var recovered = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", "default", "BUSINESS_COUNTS", null);
                if (Math.addExact(baseline.path("counts").path("list").longValue(), 1) != recovered.path("counts").path("list").longValue()) {
                    throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
                }
                ((com.fasterxml.jackson.databind.node.ObjectNode) observations.get(observations.size() - 1)).put("recoveryReadinessStatus", 200).set("recovered", recovered);
            }
        }
        requireRunningProcesses(root, manifestFile, manifest, digest, evidence, java.util.Set.of("yuheng", "shoubing"));
        if (!digest.equals(UnifiedIdentityLiveClient.sha256(UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024)))) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
        proof.put("status", "PASSED").put("recordedAt", Instant.now().toString());
        XingyuanNativeAuthenticationIT.publishNewNativeEvidence(root, evidence.resolve("resource-spool-readiness.json"), proof);
    }

    private static URI yuhengManagementReadiness(JsonNode manifest) {
        URI origin = GatewayAdminTestClient.reviewedServiceOrigin(manifest.path("managementOrigins").path("yuheng").asText());
        return origin.resolve("/actuator/health/readiness");
    }

    private static void awaitYuhengReadiness(HttpClient http, URI endpoint, Duration timeout, boolean expected) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var body = new ByteArrayOutputStream();
            var response = http.send(HttpRequest.newBuilder(endpoint)
                    .GET().timeout(Duration.ofNanos(Math.max(1, deadline - System.nanoTime()))).build(), boundedBody(body));
            var value = UnifiedIdentityLiveClient.strictJson().readTree(body.toByteArray());
            if (response.headers().firstValue("Location").isPresent() || response.headers().firstValue("Set-Cookie").isPresent()) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
            if (response.statusCode() == (expected ? 200 : 503) && (expected ? "UP" : "DOWN").equals(value.path("status").asText())) { return; }
            Thread.sleep(100);
        }
        throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
    }

    static long securityMetricCount(JsonNode snapshot, String signal, String category) {
        var matches = java.util.stream.StreamSupport.stream(snapshot.path("samples").spliterator(), false)
                .filter(value -> signal.equals(value.path("signal").asText()) && category.equals(value.path("result").asText())).toList();
        if (matches.size() > 1) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        return matches.isEmpty() ? 0 : transactionProofLong(matches.getFirst(), "count");
    }

    private static void awaitYuhengSpoolDrained(Path root, Duration timeout) throws Exception {
        Path directory = root.resolve("evidence/oauth-spool-yuheng"); long deadline = System.nanoTime() + timeout.toNanos();
        while (deadline - System.nanoTime() > 0) {
            requirePrivateSpoolDirectory(root, directory);
            try (var files = Files.list(directory)) {
                boolean pending = false; int count = 0;
                for (var path : files.toList()) {
                    if (++count > 4096 || Files.isSymbolicLink(path)) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
                    String name = path.getFileName().toString();
                    if (name.endsWith(".dead")) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                    if (name.endsWith(".evt") || name.endsWith(".sent")) { pending = true; }
                }
                if (!pending) { return; }
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "XINGYUAN_ISOLATED_INTEGRATION", matches = "true")
    void actualYuhengSharedStateIoUnavailabilityAndFixedMetricsRecover() throws Exception {
        var client = UnifiedIdentityLiveClient.isolated(); var mapper = UnifiedIdentityLiveClient.strictJson();
        Path manifestFile = Path.of(System.getProperty("xingyuan.isolated.manifest-file", System.getenv("XINGYUAN_INTEGRATION_MANIFEST")));
        byte[] bytes = UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024); var manifest = mapper.readTree(bytes);
        Path root = Path.of(manifest.path("runtimeRoot").asText()); String digest = UnifiedIdentityLiveClient.sha256(bytes);
        Path evidence = Path.of(System.getProperty("xingyuan.isolated.evidence-directory", root.resolve("evidence").toString())); requireEvidenceDirectory(root, evidence);
        Duration timeout = Duration.ofSeconds(manifest.path("deadlines").path("httpSeconds").intValue());
        var profile = verifiedProfile(root, manifest, digest, "yuheng");
        if (!"STRICT_SHARED".equals(profile.getProperty("egon.cola.platform.tianquan.shoubing.revocation.mode")) || timeout.compareTo(Duration.ofSeconds(20)) < 0) {
            throw new IllegalArgumentException("XINGYUAN_INPUT_UNAVAILABLE");
        }
        profile.clear(); var reference = client.formal("default", client.resourceForAlias("edge-yuheng"), "admin"); client.verifiedClaims(reference);
        if (!reference.getExpiresAt().isAfter(Instant.now().plusSeconds(90))) { throw new IllegalStateException("XINGYUAN_INPUT_EXPIRED"); }
        var proof = mapper.createObjectNode().put("schemaVersion", 1).put("runId", manifest.path("runId").asText()).put("manifestSha256", digest)
                .put("scope", "YUHENG_ACTUAL_SHARED_STATE_IO_AND_METRICS_ONLY").put("tokenReference", reference.getTokenSha256()).put("tokenExpiresAt", reference.getExpiresAt().toString());
        try (var http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var api = new NativeResourceHttpProbe("yuheng", root, manifestFile, digest, http, timeout);
            api.request("GET", PLATFORM_READ_PATHS.get("yuheng"), reference, null, null, 200);
            var before = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", "default", "SECURITY_METRICS", null);
            var counters = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", "default", "BUSINESS_COUNTS", null);
            for (int call = 0; call < 8; call++) { api.request("GET", PLATFORM_READ_PATHS.get("yuheng"), reference, null, null, 200); }
            var after = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", "default", "SECURITY_METRICS", null);
            var successful = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", "default", "BUSINESS_COUNTS", null);
            if (transactionProofLong(successful.path("counts"), "list") != Math.addExact(transactionProofLong(counters.path("counts"), "list"), 8)
                    || securityMetricCount(after, "oauth.authentication", "VALID") < Math.addExact(securityMetricCount(before, "oauth.authentication", "VALID"), 8)
                    || securityMetricCount(after, "jwt.verify.duration", "SUCCESS") < Math.addExact(securityMetricCount(before, "jwt.verify.duration", "SUCCESS"), 8)
                    || securityMetricCount(after, "jwks.cache.access", "HIT") < Math.addExact(securityMetricCount(before, "jwks.cache.access", "HIT"), 8)
                    || securityMetricCount(after, "jwks.cache.access", "MISS") != securityMetricCount(before, "jwks.cache.access", "MISS")
                    || securityMetricCount(after, "jwks.cache.access", "EXPIRED") != securityMetricCount(before, "jwks.cache.access", "EXPIRED")) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
            proof.set("metricsBefore", before); proof.set("metricsAfterRepeatedValidCalls", after);
            awaitYuhengSpoolDrained(root, timeout); awaitYuhengReadiness(http, yuhengManagementReadiness(manifest), timeout, true);
            var baseline = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", "default", "BUSINESS_COUNTS", null);
            String trace = UUID.randomUUID().toString().replace("-", ""); var request = HttpRequest.newBuilder(nativeResourceEndpoint(manifest, "yuheng", "GET", PLATFORM_READ_PATHS.get("yuheng")))
                    .GET().timeout(timeout).header("X-Request-Id", UUID.randomUUID().toString()).header("traceparent", "00-" + trace + '-' + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "-01");
            byte[] token = UnifiedIdentityLiveClient.protectedBytes(reference.getTokenFile(), root.resolve("secrets"), 128 * 1024);
            try {
                if (!reference.getTokenSha256().equals(UnifiedIdentityLiveClient.sha256(token))) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
                request.header("Authorization", "Bearer " + new String(token, StandardCharsets.UTF_8));
            } finally { java.util.Arrays.fill(token, (byte) 0); }
            boolean restore = false; var body = new ByteArrayOutputStream(); HttpResponse<Void> unavailable; Instant requestedAt = Instant.now();
            try {
                restore = true;
                var paused = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", "default", "SHARED_STATE_PAUSE", null); proof.set("pause", paused);
                Instant resumesAt = transactionProofInstant(paused, "resumesAt");
                if (!resumesAt.isAfter(Instant.now().plusSeconds(5))) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                requestedAt = Instant.now(); var future = http.sendAsync(request.build(), boundedBody(body));
                try { unavailable = future.get(Math.max(1, Math.min(timeout.toNanos(), Duration.between(Instant.now(), resumesAt.minusSeconds(1)).toNanos())), TimeUnit.NANOSECONDS); }
                finally { if (!future.isDone()) { future.cancel(true); } }
                if (unavailable.statusCode() != 503 || !resumesAt.isAfter(Instant.now())) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                var error = mapper.readTree(body.toByteArray());
                if (!java.util.Set.of("OAUTH_SECURITY_STATE_TIMEOUT", "OAUTH_SECURITY_STATE_UNAVAILABLE", "OAUTH_AUDIT_DELIVERY_UNAVAILABLE").contains(error.path("code").asText())) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                awaitYuhengReadiness(http, yuhengManagementReadiness(manifest), Duration.ofSeconds(2), false);
                proof.put("unavailableCode", error.path("code").asText()).put("readinessDuringFault", 503);
            } finally {
                if (restore) { proof.set("resume", XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", "default", "SHARED_STATE_RESUME", null)); }
            }
            proof.set("unavailableRequest", mapper.valueToTree(observedHttpCall(unavailable, trace, requestedAt)));
            var denied = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", "default", "BUSINESS_COUNTS", null);
            if (!baseline.equals(denied)) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
            awaitYuhengSpoolDrained(root, timeout); awaitYuhengReadiness(http, yuhengManagementReadiness(manifest), timeout, true);
            api.request("GET", PLATFORM_READ_PATHS.get("yuheng"), reference, null, null, 200);
            var recovered = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", "default", "BUSINESS_COUNTS", null); requireOneBusinessInvocation(denied, recovered);
            var finalMetrics = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "yuheng", "default", "SECURITY_METRICS", null);
            if (securityMetricCount(finalMetrics, "oauth.authentication", "UNAVAILABLE") <= securityMetricCount(after, "oauth.authentication", "UNAVAILABLE")) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
            proof.set("businessBeforeFault", baseline); proof.set("businessAfterUnavailable", denied); proof.set("businessAfterRecovery", recovered); proof.set("metricsAfterRecovery", finalMetrics);
            proof.put("recoveryReadiness", 200).put("recoveryStatus", 200).put("repeatedValidRequestCount", 8);
        }
        requireRunningProcesses(root, manifestFile, manifest, digest, evidence, java.util.Set.of("yuheng", "shoubing"));
        proof.put("status", "PASSED").put("recordedAt", Instant.now().toString());
        XingyuanNativeAuthenticationIT.publishNewNativeEvidence(root, evidence.resolve("resource-shared-state-metrics.json"), proof);
    }

    private static void requirePrivateSpoolDirectory(Path root, Path directory) throws Exception {
        var owner = root.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        for (Path current = directory; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) { throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE"); }
            if (current.startsWith(root) && (!Files.isDirectory(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || !owner.equals(Files.getOwner(current, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    || !Files.getPosixFilePermissions(current, java.nio.file.LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------")))) {
                throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
            }
        }
    }

    @RequiredArgsConstructor(access = lombok.AccessLevel.PRIVATE)
    static final class TaskSpoolFault implements AutoCloseable {
        private final Path root;
        private final Object directoryKey;
        private final Path path;
        private final Object fileKey;
        private final long size;
        private final java.nio.file.attribute.FileTime modifiedAt;
        @Override public void close() throws Exception {
            requirePrivateSpoolDirectory(root, path.getParent());
            var directory = Files.readAttributes(path.getParent(), java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            var actual = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (!directoryKey.equals(directory.fileKey()) || !actual.isRegularFile() || !fileKey.equals(actual.fileKey()) || size != actual.size()
                    || !modifiedAt.equals(actual.lastModifiedTime()) || !Files.getOwner(root).equals(Files.getOwner(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    || !Files.getPosixFilePermissions(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------"))) {
                throw new IllegalArgumentException("XINGYUAN_TARGET_UNSAFE");
            }
            Files.delete(path);
            try (var parent = java.nio.channels.FileChannel.open(path.getParent(), java.nio.file.StandardOpenOption.READ)) { parent.force(true); }
        }
    }

    @Test
    void taskSpoolFaultRejectsForeignDirectoryBeforeAnyWrite(@org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath(); Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        var foreign = root.resolve("foreign"); Files.createDirectory(foreign, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        assertThatThrownBy(() -> createTaskSpoolFault(root, foreign, "DEAD", 512)).hasMessage("XINGYUAN_TARGET_UNSAFE");
        try (var contents = Files.list(foreign)) { assertThat(contents.count()).isZero(); }
    }

    @Test
    void taskSpoolFaultOnlyRemovesItsOwnFile(@org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath(); Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Path evidence = root.resolve("evidence"), directory = evidence.resolve("oauth-spool-yuheng");
        Files.createDirectory(evidence, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        for (String kind : List.of("DEAD", "CAPACITY")) {
            Path owned;
            try (var fault = createTaskSpoolFault(root, directory, kind, 512)) {
                owned = fault.path;
                assertThat(Files.getPosixFilePermissions(owned)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
                assertThat(Files.size(owned)).isPositive();
            }
            assertThat(Files.exists(owned)).isFalse();
        }
        var fault = createTaskSpoolFault(root, directory, "DEAD", 512);
        Files.move(fault.path, fault.path.resolveSibling("retained-original.dead"));
        Files.writeString(fault.path, "foreign replacement", java.nio.file.StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(fault.path, PosixFilePermissions.fromString("rw-------"));
        assertThatThrownBy(fault::close).hasMessage("XINGYUAN_TARGET_UNSAFE");
        assertThat(Files.readString(fault.path)).isEqualTo("foreign replacement");
    }

    @Test
    void nativeUserHttpTargetsCannotChooseAnUnreviewedHostOrMutation() {
        assertThat(nativeResourceEndpoint(testOrigins(), "native-web", "POST", "/api/v1/users").toString()).isEqualTo("https://native-web.apps.example.test/api/v1/users");
        assertThat(nativeResourceEndpoint(testOrigins(), "native-light", "POST", "/graphql").toString()).isEqualTo("https://native-light.apps.example.test/graphql");
        assertThatThrownBy(() -> nativeResourceEndpoint(testOrigins(), "native-light", "GET", "/graphql")).hasMessage("XINGYUAN_TARGET_UNSAFE");
        assertThatThrownBy(() -> nativeResourceEndpoint(testOrigins(), "native-agent", "POST", "/api/v1/users")).hasMessage("XINGYUAN_TARGET_UNSAFE");
        assertThatThrownBy(() -> nativeResourceEndpoint(testOrigins(), "native-web", "DELETE", "/api/v1/users/1")).hasMessage("XINGYUAN_TARGET_UNSAFE");
        assertThatThrownBy(() -> nativeResourceEndpoint(testOrigins(), "native-web", "GET", "/api/v1/users/../1")).hasMessage("XINGYUAN_TARGET_UNSAFE");
    }

    @Test
    void nativeAgentKnowledgeTargetsUseOnlyTheOriginalFrozenPaths() {
        assertThat(nativeResourceEndpoint(testOrigins(), "native-agent", "POST", "/api/v1/knowledge-bases").toString())
                .isEqualTo("https://native-agent.apps.example.test/api/v1/knowledge-bases");
        assertThatThrownBy(() -> nativeResourceEndpoint(testOrigins(), "native-agent", "DELETE", "/api/v1/knowledge-bases/1")).hasMessage("XINGYUAN_TARGET_UNSAFE");
        assertThatThrownBy(() -> nativeResourceEndpoint(testOrigins(), "native-agent", "GET", "/api/v1/knowledge-bases/../secrets")).hasMessage("XINGYUAN_TARGET_UNSAFE");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "XINGYUAN_ISOLATED_INTEGRATION", matches = "true")
    void nativeAgentKnowledgeBasesKeepTenantAndAuthorizationBoundariesWithoutInference() throws Exception {
        var client = UnifiedIdentityLiveClient.isolated(); var mapper = UnifiedIdentityLiveClient.strictJson();
        Path manifestFile = Path.of(System.getProperty("xingyuan.isolated.manifest-file", System.getenv("XINGYUAN_INTEGRATION_MANIFEST")));
        byte[] bytes = UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024);
        var manifest = mapper.readTree(bytes); String digest = UnifiedIdentityLiveClient.sha256(bytes);
        Path root = Path.of(manifest.path("runtimeRoot").asText());
        Path evidence = Path.of(System.getProperty("xingyuan.isolated.evidence-directory", root.resolve("evidence").toString()));
        requireEvidenceDirectory(root, evidence);
        requireRunningProcesses(root, manifestFile, manifest, digest, evidence, java.util.Set.of("native-agent"));
        var profile = verifiedProfile(root, manifest, digest, "native-agent");
        String model = profile.getProperty("egon.cola.component.rag.default-embedding-model"); profile.clear();
        if (model == null || model.isBlank() || model.length() > 32 || model.contains("${")) { throw new IllegalArgumentException("XINGYUAN_INPUT_UNAVAILABLE"); }
        String resource = client.resourceForProcess("native-agent");
        var first = client.formal("default", resource, "admin"); var second = client.formal("tenant-b", resource, "admin");
        var viewer = client.formal("default", resource, "viewer");
        client.verifiedClaims(first); client.verifiedClaims(second); client.verifiedClaims(viewer);
        String code = "xingyuan-it-" + UUID.randomUUID();
        var body = mapper.createObjectNode().put("code", code).put("name", code).put("description", "Task-owned knowledge metadata verification")
                .put("embeddingModel", model).put("chunkStrategy", "TOKEN");
        body.putObject("chunkConfig").put("maxTokensPerChunk", 256).put("overlapTokens", 32).put("minChunkChars", 1).putArray("headingLevels");
        try (var transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(manifest.path("deadlines").path("httpSeconds").intValue()))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            var http = new NativeResourceHttpProbe("native-agent", root, manifestFile, digest, transport,
                    Duration.ofSeconds(manifest.path("deadlines").path("httpSeconds").intValue()));
            var createdA = http.request("POST", "/api/v1/knowledge-bases", first, body, null, 200);
            var createdB = http.request("POST", "/api/v1/knowledge-bases", second, body, null, 200);
            String idA = requireAgentKnowledgeBase(createdA, body), idB = requireAgentKnowledgeBase(createdB, body);
            assertThat(idA).isNotEqualTo(idB);
            assertThat(requireAgentKnowledgeBase(http.request("GET", "/api/v1/knowledge-bases/" + idA, first, null, null, 200), body)).isEqualTo(idA);
            assertThat(requireAgentKnowledgeBase(http.request("GET", "/api/v1/knowledge-bases/" + idB, second, null, null, 200), body)).isEqualTo(idB);
            String list = "/api/v1/knowledge-bases?page=1&size=100&keyword=" + code;
            var pageA = http.request("GET", list, first, null, null, 200); var pageB = http.request("GET", list, second, null, null, 200);
            requireAgentKnowledgePage(pageA, idA, body, 1); requireAgentKnowledgePage(pageB, idB, body, 1);
            assertThat(requireAgentKnowledgeBase(http.request("GET", "/api/v1/knowledge-bases/" + idA, first, null, second.getTenantId(), 200), body)).isEqualTo(idA);
            var foreign = http.request("GET", "/api/v1/knowledge-bases/" + idA, second, null, null, 404);
            assertThat(foreign.path("code").asText()).isEqualTo("KNOWLEDGE_BASE_NOT_FOUND");
            http.request("GET", "/api/v1/knowledge-bases/" + idA, null, null, null, 401);
            var deniedBody = body.deepCopy().put("code", code + "-deny");
            http.request("POST", "/api/v1/knowledge-bases", viewer, deniedBody, null, 403);
            requireAgentKnowledgePage(http.request("GET", list + "-deny", first, null, null, 200), null, deniedBody, 0);
            assertThat(requireAgentKnowledgeBase(http.request("GET", "/api/v1/knowledge-bases/" + idA, first, null, null, 200), body)).isEqualTo(idA);
            var proof = mapper.createObjectNode().put("schemaVersion", 1).put("runId", manifest.path("runId").asText()).put("manifestSha256", digest)
                    .put("status", "PASSED").put("scope", "AGENT_KNOWLEDGE_METADATA_ONLY").put("resourceUri", first.getResourceUri().toASCIIString())
                    .put("tokenAReference", first.getTokenSha256()).put("tokenBReference", second.getTokenSha256())
                    .put("knowledgeBaseAId", idA).put("knowledgeBaseBId", idB)
                    .put("baseAReference", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(createdA)))
                    .put("baseBReference", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(createdB)))
                    .put("pageAReference", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(pageA)))
                    .put("pageBReference", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(pageB)))
                    .put("viewerDeniedStatus", 403).put("deniedRecords", 0).put("foreignTenantCode", "KNOWLEDGE_BASE_NOT_FOUND")
                    .put("bareStatus", 401).put("recordedAt", Instant.now().toString());
            client.publishNativeAgentKnowledgeProof(evidence, proof);
        }
    }

    static String requireAgentKnowledgeBase(JsonNode actual, JsonNode expected) {
        if (actual == null || !actual.isObject() || !actual.path("knowledgeBaseId").asText().matches("[1-9][0-9]{0,18}")) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
        String id = actual.path("knowledgeBaseId").asText();
        try { if (Long.parseLong(id) <= 0) { throw new NumberFormatException(); } }
        catch (NumberFormatException invalid) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        for (String field : List.of("code", "name", "embeddingModel", "chunkStrategy")) {
            if (!expected.path(field).asText().equals(actual.path(field).asText())) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        }
        for (String field : List.of("maxTokensPerChunk", "overlapTokens")) {
            if (!actual.path("chunkConfig").path(field).isIntegralNumber() || !actual.path("chunkConfig").path(field).canConvertToInt()
                    || actual.path("chunkConfig").path(field).intValue() != expected.path("chunkConfig").path(field).intValue()) {
                throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
            }
        }
        if (!"ACTIVE".equals(actual.path("status").asText()) || !actual.path("documentCount").isIntegralNumber() || !actual.path("documentCount").canConvertToLong()
                || actual.path("documentCount").longValue() != 0) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
        for (String field : List.of("createdAt", "updatedAt")) {
            if (!actual.path(field).isTextual() || !actual.path(field).textValue().endsWith("Z")) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
            try { Instant.parse(actual.path(field).textValue()); } catch (java.time.format.DateTimeParseException invalid) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        }
        return id;
    }

    static void requireAgentKnowledgePage(JsonNode page, String expectedId, JsonNode body, int total) {
        if (page == null || !page.path("success").isBoolean() || !page.path("success").booleanValue() || !page.path("records").isArray()
                || page.path("records").size() != total || !page.path("page").path("total").isIntegralNumber() || !page.path("page").path("total").canConvertToLong()
                || page.path("page").path("total").longValue() != total) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
        if (total == 1 && !expectedId.equals(requireAgentKnowledgeBase(page.path("records").get(0), body))) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
    }

    @Test
    void agentMetadataAndPageCannotSubstituteAnotherRowOrIgnoreFrozenConfiguration() {
        var mapper = UnifiedIdentityLiveClient.strictJson();
        var body = mapper.createObjectNode().put("code", "owned-base").put("name", "Owned base").put("embeddingModel", "configured-model").put("chunkStrategy", "TOKEN");
        body.putObject("chunkConfig").put("maxTokensPerChunk", 256).put("overlapTokens", 32);
        var actual = body.deepCopy().put("knowledgeBaseId", 11).put("status", "ACTIVE").put("documentCount", 0)
                .put("createdAt", "2026-10-15T00:00:00Z").put("updatedAt", "2026-10-15T00:00:00Z");
        assertThat(requireAgentKnowledgeBase(actual, body)).isEqualTo("11");
        var changedConfig = actual.deepCopy(); changedConfig.withObject("chunkConfig").put("maxTokensPerChunk", 512);
        assertThatThrownBy(() -> requireAgentKnowledgeBase(changedConfig, body)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        var page = mapper.createObjectNode().put("success", true); page.putArray("records").add(actual); page.putObject("page").put("total", 1);
        assertThatCode(() -> requireAgentKnowledgePage(page, "11", body, 1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> requireAgentKnowledgePage(page, "12", body, 1)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        assertThatThrownBy(() -> requireAgentKnowledgePage(page, null, body, 0)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        var empty = mapper.createObjectNode().put("success", true); empty.putArray("records"); empty.putObject("page").put("total", 0);
        assertThatCode(() -> requireAgentKnowledgePage(empty, null, body, 0)).doesNotThrowAnyException();
        var overflow = actual.deepCopy().put("documentCount", new java.math.BigInteger("18446744073709551616"));
        assertThatThrownBy(() -> requireAgentKnowledgeBase(overflow, body)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
    }

    static void requireGraphQlUserAliases(JsonNode response, JsonNode expected) {
        if (response == null || !response.isObject() || response.has("errors") && (!response.path("errors").isArray() || !response.path("errors").isEmpty())
                || !response.path("data").isObject() || !expected.equals(response.path("data").path("left")) || !expected.equals(response.path("data").path("right"))) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
    }

    static void requireGraphQlMissingAliases(JsonNode response) {
        if (response == null || !response.path("data").isObject() || !response.path("data").path("left").isNull() || !response.path("data").path("right").isNull()
                || !response.path("errors").isArray() || response.path("errors").isEmpty()) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        for (var error : response.path("errors")) {
            if (!"USER_NOT_FOUND".equals(error.path("extensions").path("code").asText())) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        }
    }

    static void requireLightGraphQlScopeDenial(JsonNode response) {
        if (!response.path("data").isObject() || response.path("data").size() != 2
                || !response.path("data").path("left").isNull() || !response.path("data").path("right").isNull()
                || !response.path("errors").isArray() || response.path("errors").size() != 2) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        var aliases = new java.util.HashSet<String>();
        for (var error : response.path("errors")) {
            if (!"AUTHORIZATION_DENIED".equals(error.path("extensions").path("code").asText()) || !error.path("path").isArray() || error.path("path").size() != 1
                    || !error.path("path").get(0).isTextual() || !aliases.add(error.path("path").get(0).textValue())) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
        }
        if (!aliases.equals(java.util.Set.of("left", "right"))) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
    }

    @Test
    void lightGraphQlScopeDenialRequiresBothActualAliasesAndNoData() {
        var mapper = UnifiedIdentityLiveClient.strictJson(); var response = mapper.createObjectNode();
        response.putObject("data").putNull("left").putNull("right"); var errors = response.putArray("errors");
        for (String alias : List.of("left", "right")) {
            var error = errors.addObject(); error.putObject("extensions").put("code", "AUTHORIZATION_DENIED"); error.putArray("path").add(alias);
        }
        assertThatCode(() -> requireLightGraphQlScopeDenial(response)).doesNotThrowAnyException();
        var missing = response.deepCopy(); missing.withArray("errors").remove(1);
        assertThatThrownBy(() -> requireLightGraphQlScopeDenial(missing)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        var duplicate = response.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) duplicate.path("errors").get(1)).putArray("path").add("left");
        assertThatThrownBy(() -> requireLightGraphQlScopeDenial(duplicate)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        var data = response.deepCopy(); data.withObject("data").putObject("left").put("id", "11");
        assertThatThrownBy(() -> requireLightGraphQlScopeDenial(data)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        var unavailable = response.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) unavailable.path("errors").get(0).path("extensions")).put("code", "OAUTH_AUDIT_DELIVERY_UNAVAILABLE");
        assertThatThrownBy(() -> requireLightGraphQlScopeDenial(unavailable)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "XINGYUAN_ISOLATED_INTEGRATION", matches = "true")
    void nativeLightGraphQlAliasesUseTheSameVerifiedTenantAsRest() throws Exception {
        var client = UnifiedIdentityLiveClient.isolated();
        var mapper = UnifiedIdentityLiveClient.strictJson();
        Path manifestFile = Path.of(System.getProperty("xingyuan.isolated.manifest-file", System.getenv("XINGYUAN_INTEGRATION_MANIFEST")));
        byte[] bytes = UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024);
        var manifest = mapper.readTree(bytes); String digest = UnifiedIdentityLiveClient.sha256(bytes);
        Path root = Path.of(manifest.path("runtimeRoot").asText());
        Path evidence = Path.of(System.getProperty("xingyuan.isolated.evidence-directory", root.resolve("evidence").toString()));
        requireEvidenceDirectory(root, evidence);
        requireRunningProcesses(root, manifestFile, manifest, digest, evidence, java.util.Set.of("native-light"));
        var first = client.formal("default", client.resourceForProcess("native-light"), "admin");
        var second = client.formal("tenant-b", first.getResourceKey(), "admin");
        client.verifiedClaims(first); client.verifiedClaims(second); client.bindNativeTenantReadEndpoints(first, second);
        String firstPath = first.getReadEndpoint().getPath(), secondPath = second.getReadEndpoint().getPath();
        String firstId = firstPath.substring(firstPath.lastIndexOf('/') + 1), secondId = secondPath.substring(secondPath.lastIndexOf('/') + 1);
        var query = mapper.createObjectNode().put("query", "query OwnedUser($id: ID!) { left: user(id: $id) { id name email status } right: user(id: $id) { id name email status } }");
        var bodyA = query.deepCopy(); bodyA.putObject("variables").put("id", firstId);
        var bodyB = query.deepCopy(); bodyB.putObject("variables").put("id", secondId);
        var asyncObservations = mapper.createArrayNode();
        try (var transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(manifest.path("deadlines").path("httpSeconds").intValue()))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            var http = new NativeResourceHttpProbe("native-light", root, manifestFile, digest, transport,
                    Duration.ofSeconds(manifest.path("deadlines").path("httpSeconds").intValue()));
            var restA = http.request("GET", firstPath, first, null, null, 200);
            var restB = http.request("GET", secondPath, second, null, null, 200);
            var resultA = http.observedGraphQl(first, bodyA, null, 2, 2, asyncObservations);
            var resultB = http.observedGraphQl(second, bodyB, null, 2, 2, asyncObservations);
            requireGraphQlUserAliases(resultA, restA); requireGraphQlUserAliases(resultB, restB);
            var forged = http.request("POST", "/graphql", first, bodyA, second.getTenantId(), 200);
            requireGraphQlUserAliases(forged, restA);
            requireGraphQlMissingAliases(http.request("POST", "/graphql", second, bodyA, null, 200));
            var identityOnly = acquireIdentityOnlyProbe(client, root, evidence, manifestFile, manifest, digest, "default", first.getResourceKey());
            var beforeScope = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "native-light", "default", "BUSINESS_COUNTS", null);
            requireLightGraphQlScopeDenial(http.observedGraphQl(identityOnly, bodyA, null, 2, 0, asyncObservations));
            var afterScope = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "native-light", "default", "BUSINESS_COUNTS", null);
            UnifiedIdentityLiveClient.requireOnlyBusinessMethodDelta(beforeScope, afterScope, "get", 0);
            http.request("POST", "/graphql", null, bodyA, null, 401);
            var afterBare = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "native-light", "default", "BUSINESS_COUNTS", null);
            UnifiedIdentityLiveClient.requireOnlyBusinessMethodDelta(afterScope, afterBare, "get", 0);
            requireGraphQlUserAliases(http.request("POST", "/graphql", first, bodyA, null, 200), restA);
            var afterRecovery = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "native-light", "default", "BUSINESS_COUNTS", null);
            UnifiedIdentityLiveClient.requireOnlyBusinessMethodDelta(afterBare, afterRecovery, "get", 2);
            client.requireNativeTenantIsolationProof(first, second);
            var proof = mapper.createObjectNode().put("schemaVersion", 1).put("runId", manifest.path("runId").asText()).put("manifestSha256", digest)
                    .put("status", "PASSED").put("scope", "LIGHT_GRAPHQL_TWO_TENANT_READ").put("resourceUri", first.getResourceUri().toASCIIString())
                    .put("tokenAReference", first.getTokenSha256()).put("tokenBReference", second.getTokenSha256())
                    .put("queryReference", UnifiedIdentityLiveClient.sha256(query.path("query").asText().getBytes(StandardCharsets.UTF_8)))
                    .put("tenantAResponseReference", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(resultA)))
                    .put("tenantBResponseReference", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(resultB)))
                    .put("forgedHeaderResponseReference", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(forged)))
                    .put("identityOnlyTokenReference", identityOnly.getTokenSha256()).put("scopeDeniedCode", "AUTHORIZATION_DENIED")
                    .put("foreignTenantCode", "USER_NOT_FOUND").put("bareStatus", 401).put("recordedAt", Instant.now().toString());
            proof.set("asyncObservations", asyncObservations);
            proof.set("beforeScopeDenied", beforeScope); proof.set("afterScopeDenied", afterScope); proof.set("afterBare", afterBare); proof.set("afterRecovery", afterRecovery);
            client.publishNativeGraphQlProof(evidence, proof);
        }
    }

    @Test
    void graphQlEnvelopeCannotHideErrorsOrAnotherTenantInOneAlias() {
        var mapper = UnifiedIdentityLiveClient.strictJson();
        var user = mapper.createObjectNode().put("id", "11").put("name", "owned").put("email", "owned@xingyuan.invalid").put("status", "ACTIVE");
        var success = mapper.createObjectNode(); success.putObject("data").set("left", user); success.withObject("data").set("right", user);
        assertThatCode(() -> requireGraphQlUserAliases(success, user)).doesNotThrowAnyException();
        var mixed = success.deepCopy(); mixed.withObject("data").set("right", user.deepCopy().put("id", "12"));
        assertThatThrownBy(() -> requireGraphQlUserAliases(mixed, user)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        var partial = success.deepCopy(); partial.putArray("errors").addObject().putObject("extensions").put("code", "AUTHORIZATION_DENIED");
        assertThatThrownBy(() -> requireGraphQlUserAliases(partial, user)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        var missing = mapper.createObjectNode(); missing.putObject("data").putNull("left").putNull("right");
        missing.putArray("errors").addObject().putObject("extensions").put("code", "USER_NOT_FOUND");
        assertThatCode(() -> requireGraphQlMissingAliases(missing)).doesNotThrowAnyException();
        assertThatThrownBy(() -> requireGraphQlMissingAliases(success)).hasMessage("XINGYUAN_VERIFICATION_FAILED");
    }

    static void requireWebGraphQlDenied(JsonNode response, String alias, String code) {
        if (!response.has("data") || !response.path("data").isNull() || !response.path("errors").isArray() || response.path("errors").size() != 1) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
        var error = response.path("errors").get(0);
        if (!error.path("path").isArray() || error.path("path").size() != 1 || !alias.equals(error.path("path").get(0).asText())
                || !code.equals(error.path("extensions").path("code").asText()) || !error.path("extensions").path("traceId").isTextual()
                || !error.path("extensions").path("timestamp").isTextual() || !error.path("extensions").path("fieldErrors").isObject()) {
            throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "XINGYUAN_ISOLATED_INTEGRATION", matches = "true")
    void actualNativeWebGraphQlKeepsItsNonNullContractAndVerifiedTenant() throws Exception {
        var client = UnifiedIdentityLiveClient.isolated(); var mapper = UnifiedIdentityLiveClient.strictJson();
        Path manifestFile = Path.of(System.getProperty("xingyuan.isolated.manifest-file", System.getenv("XINGYUAN_INTEGRATION_MANIFEST")));
        byte[] bytes = UnifiedIdentityLiveClient.protectedBytes(manifestFile, null, 128 * 1024); var manifest = mapper.readTree(bytes);
        Path root = Path.of(manifest.path("runtimeRoot").asText()); String digest = UnifiedIdentityLiveClient.sha256(bytes);
        Path evidence = Path.of(System.getProperty("xingyuan.isolated.evidence-directory", root.resolve("evidence").toString())); requireEvidenceDirectory(root, evidence);
        requireRunningProcesses(root, manifestFile, manifest, digest, evidence, java.util.Set.of("native-web", "shoubing", "jianshen"));
        String resource = client.resourceForProcess("native-web"); var a = client.formal("default", resource, "admin"); var b = client.formal("tenant-b", resource, "admin");
        client.verifiedClaims(a); client.verifiedClaims(b);
        var scopeOnly = acquireIdentityOnlyProbe(client, root, evidence, manifestFile, manifest, digest, "default", resource);
        Duration timeout = Duration.ofSeconds(manifest.path("deadlines").path("httpSeconds").intValue());
        var proof = mapper.createObjectNode().put("schemaVersion", 1).put("runId", manifest.path("runId").asText()).put("manifestSha256", digest)
                .put("scope", "WEB_GRAPHQL_ACTUAL_USER_ALIASES_AND_CONTEXT_ONLY").put("tokenAReference", a.getTokenSha256()).put("tokenBReference", b.getTokenSha256());
        var observations = proof.putArray("observations"); var asyncObservations = proof.putArray("asyncObservations");
        try (var http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var api = new NativeResourceHttpProbe("native-web", root, manifestFile, digest, http, timeout); JsonNode first = null;
            for (var reference : List.of(a, b)) {
                String marker = "xingyuan-it-" + manifest.path("runId").asText() + "-graphql-" + UUID.randomUUID();
                var created = api.request("POST", "/api/v1/users", reference, mapper.createObjectNode().put("name", marker).put("email", marker + "@xingyuan.invalid"), null, 201);
                long id = responseId(created); var expected = mapper.createObjectNode();
                for (String field : List.of("id", "name", "email", "status")) { expected.set(field, created.path(field)); }
                expected.put("id", Long.toString(id));
                String query = "query($id: ID!) { left: user(id: $id) { id name email status } right: user(id: $id) { id name email status } }";
                var request = mapper.createObjectNode().put("query", query); request.putObject("variables").put("id", Long.toString(id));
                var before = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "native-web", reference.getRealmCode(), "BUSINESS_COUNTS", null);
                var actual = api.observedGraphQl(reference, request, null, 2, 2, asyncObservations);
                requireGraphQlUserAliases(actual, expected);
                var after = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "native-web", reference.getRealmCode(), "BUSINESS_COUNTS", null);
                if (transactionProofLong(after.path("counts"), "getUser") != Math.addExact(transactionProofLong(before.path("counts"), "getUser"), 2)
                        || !before.path("counts").path("createUser").equals(after.path("counts").path("createUser"))) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                var forged = api.request("POST", "/graphql", reference, request, reference == a ? b.getTenantId() : a.getTenantId(), 200); requireGraphQlUserAliases(forged, expected);
                var item = observations.addObject().put("realmCode", reference.getRealmCode()).put("rowReference", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(expected)))
                        .put("queryReference", UnifiedIdentityLiveClient.sha256(query.getBytes(StandardCharsets.UTF_8))).put("responseReference", UnifiedIdentityLiveClient.sha256(mapper.writeValueAsBytes(actual)));
                item.set("before", before); item.set("afterAliases", after);
                if (reference == a) {
                    first = created; var deniedRequest = mapper.createObjectNode().put("query", "query($id: ID!) { denied: user(id: $id) { id name email status } }");
                    deniedRequest.putObject("variables").put("id", Long.toString(id));
                    var beforeDenied = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "native-web", "default", "BUSINESS_COUNTS", null);
                    var denied = api.observedGraphQl(scopeOnly, deniedRequest, null, 1, 0, asyncObservations); requireWebGraphQlDenied(denied, "denied", "AUTHORIZATION_DENIED");
                    api.request("POST", "/graphql", null, deniedRequest, null, 401);
                    var afterDenied = XingyuanNativeAuthenticationIT.invokeApplicationProbe(root, evidence, manifestFile, manifest, digest, "native-web", "default", "BUSINESS_COUNTS", null);
                    if (!beforeDenied.equals(afterDenied)) { throw new IllegalStateException("XINGYUAN_VERIFICATION_FAILED"); }
                    item.put("missingScopeGraphQlStatus", 200).put("bareHttpStatus", 401); item.set("afterDenied", afterDenied);
                } else {
                    var foreign = mapper.createObjectNode().put("query", "query($id: ID!) { foreign: user(id: $id) { id name email status } }");
                    foreign.putObject("variables").put("id", Long.toString(responseId(first)));
                    requireWebGraphQlDenied(api.request("POST", "/graphql", b, foreign, null, 200), "foreign", "ORG_NOT_FOUND");
                    requireGraphQlUserAliases(api.request("POST", "/graphql", b, request, null, 200), expected);
                    item.put("foreignTenantCode", "ORG_NOT_FOUND");
                }
            }
        }
        proof.put("status", "PASSED").put("recordedAt", Instant.now().toString());
        XingyuanNativeAuthenticationIT.publishNewNativeEvidence(root, evidence.resolve("native-web-graphql.json"), proof);
    }

    @Test
    void webGraphQlDenialCannotHideTheOriginalNonNullPropagationOrPath() {
        var mapper = UnifiedIdentityLiveClient.strictJson(); var response = mapper.createObjectNode().putNull("data");
        var error = response.putArray("errors").addObject(); error.putArray("path").add("denied");
        error.putObject("extensions").put("code", "AUTHORIZATION_DENIED").put("traceId", "controlled").put("timestamp", "2026-10-15T00:00:00Z").putObject("fieldErrors");
        assertThatCode(() -> requireWebGraphQlDenied(response, "denied", "AUTHORIZATION_DENIED")).doesNotThrowAnyException();
        response.putObject("data").putNull("denied");
        assertThatThrownBy(() -> requireWebGraphQlDenied(response, "denied", "AUTHORIZATION_DENIED")).hasMessage("XINGYUAN_VERIFICATION_FAILED");
    }

    @Test
    void sqlIsolationProofRejectsCrossTenantAndDuplicateRows() {
        var first = new NativeSqlRowVO(11, 101, "ext-1", "owned-a", "a@xingyuan.invalid", "ACTIVE");
        var second = new NativeSqlRowVO(12, 102, "ext-1", "owned-b", "b@xingyuan.invalid", "ACTIVE");
        assertThatCode(() -> requireOwnedRows(List.of(first, second), 101, 102, 11, 12, "owned-a", "owned-b"))
                .doesNotThrowAnyException();
        var foreign = new NativeSqlRowVO(12, 101, "ext-1", "owned-b", "b@xingyuan.invalid", "ACTIVE");
        assertThatThrownBy(() -> requireOwnedRows(List.of(first, foreign), 101, 102, 11, 12, "owned-a", "owned-b"))
                .hasMessage("XINGYUAN_VERIFICATION_FAILED");
        assertThatThrownBy(() -> requireOwnedRows(List.of(first, first), 101, 102, 11, 12, "owned-a", "owned-b"))
                .hasMessage("XINGYUAN_VERIFICATION_FAILED");
        assertThatThrownBy(() -> requireOwnedRows(List.of(first), 101, 102, 11, 12, "owned-a", "owned-b"))
                .hasMessage("XINGYUAN_VERIFICATION_FAILED");
    }

    @Test
    void nativeSqlTargetsRejectForeignReceiptsAndIdentifiersBeforeConnecting() {
        var mapper = UnifiedIdentityLiveClient.strictJson();
        String runId = UUID.randomUUID().toString(), digest = "a".repeat(64);
        var manifest = mapper.createObjectNode().put("runId", runId);
        var receipt = mapper.createObjectNode().put("runId", runId).put("manifestSha256", digest).put("role", "light")
                .put("databaseName", "xingyuan_it_light_20261011").put("oid", 71).put("databaseOwner", "controlled_owner")
                .put("ownerComment", "xingyuan-it:" + runId + ":" + digest);
        assertThatCode(() -> requireLightReceipt(manifest, digest, receipt)).doesNotThrowAnyException();
        for (String field : List.of("databaseName", "runId", "ownerComment")) {
            var foreign = receipt.deepCopy().put(field, "foreign");
            assertThatThrownBy(() -> requireLightReceipt(manifest, digest, foreign)).hasMessage("XINGYUAN_TARGET_UNSAFE");
        }
        var fractional = receipt.deepCopy().put("oid", 71.5);
        assertThatThrownBy(() -> requireLightReceipt(manifest, digest, fractional)).hasMessage("XINGYUAN_TARGET_UNSAFE");
        assertThatThrownBy(() -> readRows(null, "public.users;SELECT 1", 101, 102)).hasMessage("XINGYUAN_TARGET_UNSAFE");
    }

    @Test
    void nativeResponseSubscriberRejectsOversizeBeforeAccumulation() {
        var output = new ByteArrayOutputStream();
        var subscriber = boundedBody(output).apply(new HttpResponse.ResponseInfo() {
            @Override public int statusCode() { return 200; }
            @Override public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(java.util.Map.of(), (name, value) -> true); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        });
        subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            @Override public void request(long count) { }
            @Override public void cancel() { }
        });
        assertThatThrownBy(() -> subscriber.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[128 * 1024 + 1]))))
                .isInstanceOf(IllegalStateException.class).hasMessage("XINGYUAN_VERIFICATION_FAILED");
        assertThat(output.size()).isZero();
    }

    @Test
    void nativeEvidenceRequiresTheCurrentPrivateDirectoryBeforeBusinessWrites(@org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Path evidence = Files.createDirectory(root.resolve("evidence"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        assertThatCode(() -> requireEvidenceDirectory(root, evidence)).doesNotThrowAnyException();
        assertThatThrownBy(() -> requireEvidenceDirectory(root, root.resolve("foreign"))).hasMessage("XINGYUAN_TARGET_UNSAFE");
        Files.setPosixFilePermissions(evidence, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThatThrownBy(() -> requireEvidenceDirectory(root, evidence)).hasMessage("XINGYUAN_TARGET_UNSAFE");
        Files.setPosixFilePermissions(evidence, PosixFilePermissions.fromString("rwx------"));
        Path link = evidence.resolve("verify-" + UUID.randomUUID());
        Files.createSymbolicLink(link, root);
        assertThatThrownBy(() -> requireEvidenceDirectory(root, link)).hasMessage("XINGYUAN_TARGET_UNSAFE");
    }
}
