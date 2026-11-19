package io.aetherstream.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.aetherstream.common.Json;
import io.aetherstream.common.pipeline.PipelineDefinition;
import io.aetherstream.compiler.PipelineCompiler;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

/** Real control-api process and Postgres; only the external coordinator is a local HTTP fixture. */
@Tag("integration")
class OrgAccessIT {
    private static final String KEY = "same-client-key";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    @TempDir Path temporary;

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void migratesLegacyDatabaseTwiceAndEnforcesOwnershipOverHttp() throws Exception {
        Path schema = Path.of(System.getProperty("aether.schema")).toAbsolutePath();
        Path root = schema.getParent().getParent().getParent();
        Path jar = buildApi(root);
        try (var postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("aether").withUsername("aether").withPassword("aether")) {
            postgres.start();
            createLegacyDatabase(postgres, schema);
            try (var coordinator = new CoordinatorFixture(postgres)) {
                List<String> indexes;
                List<String> persisted;
                String executionB;
                try (var api = startApi(jar, postgres, coordinator, "first")) {
                    assertLegacyPreserved(postgres, api);
                    indexes = organizationIndexes(postgres);
                    assertThat(indexes).anyMatch(index -> index.startsWith("pipelines:"));
                    assertThat(indexes).anyMatch(index -> index.startsWith("executions:"));
                    executionB = exerciseOrganizations(postgres, coordinator, api);
                    persisted = rows(postgres, """
                            SELECT jsonb_build_object('id',id,'org',org_id,'key',idempotency_key,
                                'state',state,'generation',generation)::text FROM executions ORDER BY id
                            """);
                }
                // A second actual application startup runs the migration again on the same data.
                try (var api = startApi(jar, postgres, coordinator, "second")) {
                    assertLegacyPreserved(postgres, api);
                    assertThat(organizationIndexes(postgres)).isEqualTo(indexes);
                    assertThat(rows(postgres, """
                            SELECT jsonb_build_object('id',id,'org',org_id,'key',idempotency_key,
                                'state',state,'generation',generation)::text FROM executions ORDER BY id
                            """)).isEqualTo(persisted);
                    assertThat(api.ok("GET", "/api/pipelines/org-a", "a", null).path("version").asInt()).isEqualTo(2);
                    api.error("GET", "/api/executions/" + executionB, "a", null, 404, "NOT_FOUND");
                    int mutations = coordinator.mutations.size();
                    assertThat(api.ok("POST", "/api/pipelines/org-b/executions", "b", Map.of("idempotencyKey", KEY))
                            .path("id").asText()).isEqualTo(executionB);
                    assertThat(coordinator.mutations).hasSize(mutations);
                    assertAuditOrganizations(postgres);
                }
            }
        }
    }

    private String exerciseOrganizations(PostgreSQLContainer<?> postgres, CoordinatorFixture coordinator, ApiProcess api)
            throws Exception {
        for (String invalid : List.of("bad_org", "bad.org", "a/b", "two words", "x".repeat(41))) {
            api.error("GET", "/api/pipelines", invalid, null, 400, "INVALID_ORG");
            api.error("POST", "/api/pipelines", invalid, pipeline("invalid"), 400, "INVALID_ORG");
        }
        // Preserve an explicitly empty field rather than letting an HTTP client omit it.
        assertInvalidWireHeader(api.port, "");
        assertThat(api.ok("GET", "/api/pipelines", "A-" + "9".repeat(38), null).isEmpty()).isTrue();
        assertThat(api.ok("GET", "/api/pipelines", "a", null).isEmpty()).isTrue();
        assertThat(api.ok("POST", "/api/pipelines", "a", pipeline("org-a")).path("version").asInt()).isEqualTo(1);
        assertThat(api.ok("POST", "/api/pipelines", "a", pipeline("org-a")).path("version").asInt()).isEqualTo(2);
        assertThat(api.ok("GET", "/api/pipelines/org-a", "a", null).path("version").asInt()).isEqualTo(2);
        assertThat(api.ok("GET", "/api/pipelines", "a", null).get(0).path("id").asText()).isEqualTo("org-a");
        assertThat(api.ok("GET", "/api/pipelines", "b", null).isEmpty()).isTrue();
        int mutations = coordinator.mutations.size();
        api.error("GET", "/api/pipelines/org-a", "b", null, 404, "NOT_FOUND");
        api.error("POST", "/api/pipelines/org-a/executions", "b", Map.of("idempotencyKey", KEY), 404, "NOT_FOUND");
        api.error("POST", "/api/pipelines", "b", pipeline("org-a"), 409, "PIPELINE_ID_TAKEN");
        assertThat(coordinator.mutations).hasSize(mutations);
        assertThat(rows(postgres, "SELECT org_id || ':' || count(*) FROM pipelines WHERE id='org-a' GROUP BY org_id"))
                .containsExactly("a:2");

        JsonNode startedA = api.ok("POST", "/api/pipelines/org-a/executions", "a", Map.of("idempotencyKey", KEY));
        String executionA = startedA.path("id").asText();
        assertThat(executionA).isNotBlank();
        assertThat(startedA.path("pipelineVersion").asInt()).isEqualTo(2);
        assertOwner(postgres, executionA, "a");
        assertThat(api.ok("GET", "/api/executions/" + executionA, "a", null).path("state").asText()).isEqualTo("RUNNING");
        assertThat(api.ok("GET", "/api/executions/current", "a", null).path("id").asText()).isEqualTo(executionA);
        assertThat(api.request("GET", "/api/executions/current", "b", null).statusCode()).isEqualTo(204);
        assertThat(api.ok("GET", "/api/executions/" + executionA + "/checkpoints", "a", null).size()).isEqualTo(1);
        assertCurrentResultsScoped(postgres, coordinator, api, executionA);
        mutations = coordinator.mutations.size();
        assertThat(api.ok("POST", "/api/pipelines/org-a/executions", "a", Map.of("idempotencyKey", KEY))
                .path("id").asText()).isEqualTo(executionA);
        api.error("POST", "/api/pipelines/missing/executions", "a", Map.of("idempotencyKey", KEY), 404, "NOT_FOUND");
        denyExecution(api, executionA, "b", KEY);
        denyExecution(api, "does-not-exist", "a", KEY);
        assertThat(coordinator.mutations).hasSize(mutations);
        assertThat(coordinator.failures).isEmpty();

        api.ok("POST", "/api/pipelines", "b", pipeline("org-b"));
        mutations = coordinator.mutations.size();
        api.error("POST", "/api/pipelines/org-b/executions", "b", Map.of("idempotencyKey", KEY), 409, "EXECUTION_BUSY");
        api.error("POST", "/api/pipelines/org-b/executions", "a", Map.of("idempotencyKey", KEY), 404, "NOT_FOUND");
        assertThat(coordinator.mutations).hasSize(mutations);
        assertThat(api.ok("GET", "/api/executions/" + executionA, "a", null).path("state").asText()).isEqualTo("RUNNING");

        JsonNode savepoint = api.ok("POST", "/api/executions/" + executionA + "/savepoints", "a", Map.of());
        String savepointId = savepoint.path("savepointId").asText();
        assertThat(savepointId).isNotBlank();
        api.ok("POST", "/api/executions/" + executionA + "/rescale", "a", Map.of());
        Map<String, String> restoreRequest = Map.of("savepointId", savepointId, "idempotencyKey", "restore-key");
        String restoredA = api.ok("POST", "/api/executions/" + executionA + "/restore", "a", restoreRequest).path("id").asText();
        assertThat(restoredA).isNotBlank().isNotEqualTo(executionA);
        assertOwner(postgres, restoredA, "a");
        mutations = coordinator.mutations.size();
        assertThat(api.ok("POST", "/api/executions/" + executionA + "/restore", "a", restoreRequest)
                .path("id").asText()).isEqualTo(restoredA);
        api.error("POST", "/api/executions/missing/restore", "a", restoreRequest, 404, "NOT_FOUND");
        denyExecution(api, restoredA, "b", "restore-key");
        assertThat(coordinator.mutations).hasSize(mutations);
        assertThat(api.ok("POST", "/api/executions/" + restoredA + "/stop", "a", Map.of())
                .path("state").asText()).isEqualTo("STOPPED");

        String executionB = api.ok("POST", "/api/pipelines/org-b/executions", "b", Map.of("idempotencyKey", KEY))
                .path("id").asText();
        assertThat(executionB).isNotBlank().isNotEqualTo(executionA);
        assertOwner(postgres, executionB, "b");
        mutations = coordinator.mutations.size();
        // b now has an idempotency hit: it still cannot use it to address a's resource.
        api.error("POST", "/api/pipelines/org-a/executions", "b", Map.of("idempotencyKey", KEY), 404, "NOT_FOUND");
        denyExecution(api, executionA, "b", KEY);
        // Restoring an owned, stopped source must not stop another org's globally active execution.
        api.error("POST", "/api/executions/" + executionA + "/restore", "a",
                Map.of("savepointId", savepointId, "idempotencyKey", "another-restore"), 409, "EXECUTION_BUSY");
        assertThat(coordinator.mutations).hasSize(mutations);
        assertThat(api.ok("GET", "/api/executions/" + executionB, "b", null).path("state").asText()).isEqualTo("RUNNING");
        api.ok("POST", "/api/executions/" + executionB + "/stop", "b", Map.of());

        // Missing X-Org is the same namespace as explicitly sending demo, not an unscoped request.
        assertThat(api.ok("GET", "/api/pipelines", null, null).size()).isEqualTo(1);
        assertThat(api.ok("GET", "/api/pipelines/legacy-pipeline", null, null).path("id").asText()).isEqualTo("legacy-pipeline");
        api.error("GET", "/api/pipelines/org-a", null, null, 404, "NOT_FOUND");
        api.ok("POST", "/api/pipelines", null, pipeline("default-org"));
        assertThat(api.ok("GET", "/api/pipelines/default-org", "demo", null).path("id").asText()).isEqualTo("default-org");
        String executionDemo = api.ok("POST", "/api/pipelines/default-org/executions", null, Map.of("idempotencyKey", KEY))
                .path("id").asText();
        assertOwner(postgres, executionDemo, "demo");
        mutations = coordinator.mutations.size();
        assertThat(api.ok("POST", "/api/pipelines/default-org/executions", "demo", Map.of("idempotencyKey", KEY))
                .path("id").asText()).isEqualTo(executionDemo);
        assertThat(coordinator.mutations).hasSize(mutations);
        api.ok("POST", "/api/executions/" + executionDemo + "/stop", null, Map.of());
        assertThat(rows(postgres, "SELECT idempotency_key FROM executions WHERE id IN ('" + executionA + "','"
                + executionB + "','" + executionDemo + "')"))
                .hasSize(3).doesNotContain(KEY).doesNotHaveDuplicates();
        assertAuditOrganizations(postgres);
        assertThat(coordinator.failures).isEmpty();
        return executionB;
    }

    private static void denyExecution(ApiProcess api, String id, String org, String key) throws Exception {
        api.error("GET", "/api/executions/" + id, org, null, 404, "NOT_FOUND");
        api.error("GET", "/api/executions/" + id + "/checkpoints", org, null, 404, "NOT_FOUND");
        api.error("POST", "/api/executions/" + id + "/stop", org, Map.of(), 404, "NOT_FOUND");
        api.error("POST", "/api/executions/" + id + "/savepoints", org, Map.of(), 404, "NOT_FOUND");
        api.error("POST", "/api/executions/" + id + "/restore", org,
                Map.of("savepointId", "fixture-savepoint", "idempotencyKey", key), 404, "NOT_FOUND");
        api.error("POST", "/api/executions/" + id + "/rescale", org, Map.of(), 404, "NOT_FOUND");
    }

    private static void assertCurrentResultsScoped(PostgreSQLContainer<?> postgres, CoordinatorFixture coordinator,
            ApiProcess api, String executionId) throws Exception {
        try (Connection connection = connection(postgres)) {
            for (String sql : List.of(
                    "INSERT INTO window_results(execution_id,sink_id,key,window_start,window_end,values,event_count,generation) VALUES (?,'speedSink','truck-a',0,60000,'{\"avgSpeedKmh\":12}'::jsonb,1,1)",
                    "INSERT INTO alerts(execution_id,sink_id,alert_id,key,rule_id,message,event_time,generation) VALUES (?,'alertSink','alert-a','truck-a','rule-a','private a alert',60000,1)",
                    "INSERT INTO dead_letters(execution_id,event_id,kind,reason,partition,\"offset\",event_time,generation) VALUES (?,'event-a','LATE','private a rejection',0,1,0,1)")) {
                try (var insert = connection.prepareStatement(sql)) {
                    insert.setString(1, executionId);
                    insert.executeUpdate();
                }
            }
        }
        for (String result : List.of("windows", "alerts", "dead-letters")) {
            assertThat(api.ok("GET", "/api/results/" + result, "a", null).size()).isEqualTo(1);
            assertThat(api.ok("GET", "/api/results/" + result, "b", null).isEmpty()).isTrue();
        }
        assertThat(api.ok("GET", "/api/results/summary", "a", null).path("windowsWritten").asInt()).isEqualTo(1);
        JsonNode foreignSummary = api.ok("GET", "/api/results/summary", "b", null);
        assertThat(foreignSummary.path("executionId").asText()).isEmpty();
        assertThat(foreignSummary.path("windowsWritten").asInt()).isZero();
        api.error("POST", "/api/compare", "b", Map.of(), 409, "NO_EXECUTION");
        int mutations = coordinator.mutations.size();
        for (String chaos : List.of("late-events", "duplicates", "malformed", "slow-sink", "kill-worker")) {
            api.error("POST", "/api/chaos/" + chaos, "b", Map.of("count", 1, "delayMs", 1), 404, "NOT_FOUND");
            // demo owns a legacy stopped execution, but not the global active execution.
            api.error("POST", "/api/chaos/" + chaos, null, Map.of("count", 1, "delayMs", 1), 404, "NOT_FOUND");
        }
        assertThat(coordinator.mutations).hasSize(mutations);
    }

    private static ObjectNode pipeline(String id) {
        return Json.MAPPER.valueToTree(new PipelineDefinition(id, 1, "Organization integration " + id,
                PipelineCompiler.demoPipeline().nodes(), PipelineCompiler.demoPipeline().edges()));
    }

    private static void createLegacyDatabase(PostgreSQLContainer<?> postgres, Path schema) throws Exception {
        // Use the contract's complete schema but deliberately recreate its pre-organization shape.
        String legacy = Files.readString(schema).lines().filter(line -> !line.matches("\\s*org_id\\s+.*"))
                .collect(Collectors.joining("\n"));
        try (Connection connection = connection(postgres); var statement = connection.createStatement()) {
            statement.execute(legacy);
            try (var insert = connection.prepareStatement("INSERT INTO pipelines(id,version,name,definition) VALUES (?,1,?,?::jsonb)")) {
                insert.setString(1, "legacy-pipeline");
                insert.setString(2, "preserved legacy pipeline");
                insert.setString(3, pipeline("legacy-pipeline").toString());
                insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO executions(id,pipeline_id,pipeline_version,plan,state,generation,idempotency_key)
                    VALUES ('legacy-execution','legacy-pipeline',1,?::jsonb,'STOPPED',7,'legacy-key')
                    """)) {
                insert.setString(1, Json.MAPPER.writeValueAsString(new PipelineCompiler().plan(
                        Json.MAPPER.treeToValue(pipeline("legacy-pipeline"), PipelineDefinition.class), 6)));
                insert.executeUpdate();
            }
            statement.execute("""
                    INSERT INTO checkpoints(execution_id,checkpoint_id,generation,state,source_offsets,completed_at)
                    VALUES ('legacy-execution',0,7,'COMPLETED','{"0":17}'::jsonb,now())
                    """);
        }
        assertThat(rows(postgres, "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND column_name='org_id'"))
                .isEmpty();
    }

    private static void assertLegacyPreserved(PostgreSQLContainer<?> postgres, ApiProcess api) throws Exception {
        assertThat(rows(postgres, "SELECT name || ':' || org_id FROM pipelines WHERE id='legacy-pipeline'"))
                .containsExactly("preserved legacy pipeline:demo");
        assertThat(rows(postgres, "SELECT org_id || ':' || generation || ':' || (plan->>'pipelineId') FROM executions WHERE id='legacy-execution'"))
                .containsExactly("demo:7:legacy-pipeline");
        assertThat(rows(postgres, """
                SELECT table_name || ':' || is_nullable || ':' || column_default
                FROM information_schema.columns WHERE table_schema='public' AND column_name='org_id'
                    AND table_name IN ('pipelines','executions') ORDER BY table_name
                """)).hasSize(2).allSatisfy(column -> assertThat(column).contains(":NO:").contains("'demo'"));
        assertThat(api.ok("GET", "/api/pipelines/legacy-pipeline", null, null).path("id").asText()).isEqualTo("legacy-pipeline");
        assertThat(api.ok("GET", "/api/executions/legacy-execution", "demo", null).path("generation").asInt()).isEqualTo(7);
        assertThat(api.ok("GET", "/api/executions/legacy-execution/checkpoints", null, null).get(0)
                .path("sourceOffsets").path("0").asInt()).isEqualTo(17);
        api.error("GET", "/api/pipelines/legacy-pipeline", "a", null, 404, "NOT_FOUND");
        api.error("GET", "/api/executions/legacy-execution", "a", null, 404, "NOT_FOUND");
    }

    private static List<String> organizationIndexes(PostgreSQLContainer<?> postgres) throws Exception {
        return rows(postgres, """
                SELECT table_class.relname || ':' || pg_get_indexdef(index_class.oid)
                FROM pg_index index_info JOIN pg_class table_class ON table_class.oid=index_info.indrelid
                JOIN pg_class index_class ON index_class.oid=index_info.indexrelid
                JOIN pg_attribute attribute ON attribute.attrelid=table_class.oid AND attribute.attnum=index_info.indkey[0]
                WHERE table_class.relname IN ('pipelines','executions') AND attribute.attname='org_id'
                ORDER BY table_class.relname,index_class.relname
                """);
    }

    private static void assertOwner(PostgreSQLContainer<?> postgres, String id, String org) throws Exception {
        try (Connection connection = connection(postgres); var query = connection.prepareStatement("SELECT org_id FROM executions WHERE id=?")) {
            query.setString(1, id);
            try (var rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(org);
                assertThat(rows.next()).isFalse();
            }
        }
    }

    private static void assertAuditOrganizations(PostgreSQLContainer<?> postgres) throws Exception {
        assertThat(rows(postgres, "SELECT action FROM audit_log WHERE actor='ui' AND (detail->>'orgId' IS NULL OR detail->>'orgId' NOT IN ('a','b','demo'))"))
                .isEmpty();
        assertThat(rows(postgres, "SELECT DISTINCT action || ':' || (detail->>'orgId') FROM audit_log WHERE actor='ui'"))
                .contains("pipeline.created:a", "pipeline.created:b", "pipeline.created:demo", "execution.started:a",
                        "execution.started:b", "execution.started:demo", "savepoint.requested:a", "execution.restored:a", "execution.rescaled:a");
    }

    private static Connection connection(PostgreSQLContainer<?> postgres) throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static List<String> rows(PostgreSQLContainer<?> postgres, String sql) throws Exception {
        try (Connection connection = connection(postgres); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (result.next()) values.add(result.getString(1));
            return values;
        }
    }

    private Path buildApi(Path root) throws Exception {
        Path log = temporary.resolve("boot-jar-build.log");
        // The test JVM holds dependency jars open on Windows; isolate outputs as well as Gradle state.
        Path buildDirectory = temporary.resolve("api-build");
        Path initScript = temporary.resolve("api-build.gradle");
        String buildPath = buildDirectory.toString().replace("\\", "/").replace("'", "\\'");
        Files.writeString(initScript, "gradle.beforeProject { p -> p.layout.buildDirectory.set(new File('"
                + buildPath + "', p.path.substring(1).replace(':', '/'))) }\n");
        List<String> command = new ArrayList<>();
        if (windows()) command.addAll(List.of("cmd.exe", "/c", "gradlew.bat"));
        else command.addAll(List.of("sh", root.resolve("gradlew").toString()));
        // The enclosing :tests:integrationTest still owns its project-cache lock.
        command.addAll(List.of("--no-daemon", "--max-workers=2", "--console=plain", "--project-cache-dir",
                temporary.resolve("gradle-project-cache").toString(), "--init-script", initScript.toString(),
                ":control-api:bootJar"));
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process process = builder.start();
        try {
            if (!process.waitFor(8, TimeUnit.MINUTES))
                throw new AssertionError("child Gradle timed out: " + logText(log));
            if (process.exitValue() != 0) throw new AssertionError("child Gradle failed: " + logText(log));
        } finally {
            terminate(process);
        }
        try (var candidates = Files.list(buildDirectory.resolve("control-api/libs"))) {
            List<Path> executableJars = candidates.filter(path -> path.toString().endsWith(".jar")).filter(path -> {
                try (JarFile file = new JarFile(path.toFile())) {
                    return file.getManifest() != null && "io.aetherstream.api.ControlApiApplication".equals(
                            file.getManifest().getMainAttributes().getValue("Start-Class"));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toList();
            assertThat(executableJars).as("bootJar must produce exactly one current executable API jar").hasSize(1);
            return executableJars.get(0);
        }
    }

    private ApiProcess startApi(Path jar, PostgreSQLContainer<?> postgres, CoordinatorFixture coordinator, String name)
            throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Path log = temporary.resolve("api-" + name + ".log");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-jar", jar.toString(), "--server.port=" + port,
                "--spring.main.banner-mode=off").redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(Map.of("PG_URL", postgres.getJdbcUrl(), "PG_USER", postgres.getUsername(),
                "PG_PASSWORD", postgres.getPassword(), "COORDINATOR_URL", coordinator.url(), "SIMULATOR_URL", coordinator.url(),
                "KAFKA_BOOTSTRAP", "127.0.0.1:1"));
        ApiProcess api = new ApiProcess(builder.start(), port, log);
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (System.nanoTime() < deadline && api.process.isAlive()) {
                try {
                    if (api.request("GET", "/api/pipelines", null, null).statusCode() == 200
                            && rows(postgres, "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND column_name='org_id'").size() == 2
                            && organizationIndexes(postgres).size() >= 2) return api;
                } catch (IOException ignored) {
                    // The process has not yet bound its HTTP port.
                }
                Thread.sleep(100);
            }
            throw new AssertionError("API did not become ready: " + logText(log));
        } catch (Throwable failure) {
            api.close();
            throw failure;
        }
    }

    private static void assertInvalidWireHeader(int port, String value) throws Exception {
        try (var socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET /api/pipelines HTTP/1.1\r\nHost: localhost\r\nX-Org:" + value
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(response).startsWith("HTTP/1.1 400").contains("INVALID_ORG");
        }
    }

    private static boolean windows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }

    private static String logText(Path log) throws IOException {
        String text = Files.readString(log);
        return text.substring(Math.max(0, text.length() - 16_000));
    }

    private static void terminate(Process process) {
        boolean interrupted = Thread.interrupted();
        List<ProcessHandle> descendants = process.descendants().sorted(Comparator.comparingLong(ProcessHandle::pid).reversed()).toList();
        try {
            descendants.forEach(ProcessHandle::destroy);
            process.destroy();
            try {
                process.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        } finally {
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
            try {
                process.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static final class ApiProcess implements AutoCloseable {
        private final Process process;
        private final int port;
        private final Path log;

        private ApiProcess(Process process, int port, Path log) { this.process = process; this.port = port; this.log = log; }

        private HttpResponse<String> request(String method, String path, String org, Object body) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
            if (org != null) builder.header("X-Org", org);
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)));
            return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        private JsonNode ok(String method, String path, String org, Object body) throws Exception {
            HttpResponse<String> response = request(method, path, org, body);
            if (response.statusCode() != 200) throw new AssertionError(method + " " + path + " org=" + org
                    + ": HTTP " + response.statusCode() + " " + response.body() + "; API log: " + logText(log));
            return Json.MAPPER.readTree(response.body());
        }

        private void error(String method, String path, String org, Object body, int status, String code) throws Exception {
            HttpResponse<String> response = request(method, path, org, body);
            assertThat(response.statusCode()).as("%s %s org=%s: %s", method, path, org, response.body()).isEqualTo(status);
            assertThat(Json.MAPPER.readTree(response.body()).path("error").asText()).isEqualTo(code);
        }

        @Override public void close() throws InterruptedException { terminate(process); }
    }

    /** Persists coordinator-owned execution inserts without Kafka, workers, or Spring test dependencies. */
    private static final class CoordinatorFixture implements AutoCloseable {
        private final PostgreSQLContainer<?> postgres;
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final List<String> mutations = new CopyOnWriteArrayList<>();
        private final List<Throwable> failures = new CopyOnWriteArrayList<>();
        private final List<JsonNode> savepoints = new CopyOnWriteArrayList<>();

        private CoordinatorFixture(PostgreSQLContainer<?> postgres) throws IOException {
            this.postgres = postgres;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(executor);
            server.start();
        }

        private String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                boolean post = exchange.getRequestMethod().equals("POST");
                if (post) mutations.add(path);
                JsonNode response;
                if (post && path.equals("/coord/executions")) {
                    JsonNode request = Json.MAPPER.readTree(exchange.getRequestBody());
                    response = insertExecution(request);
                } else if (path.startsWith("/coord/executions/")) {
                    String[] parts = path.split("/");
                    String id = parts[3];
                    if (post && path.endsWith("/stop")) {
                        update("UPDATE executions SET state='STOPPED',updated_at=now() WHERE id=?", id);
                        response = execution(id);
                    } else if (post && path.endsWith("/savepoint")) {
                        response = Json.MAPPER.valueToTree(Map.of("savepointId", "fixture-savepoint", "executionId", id,
                                "checkpointId", 0, "pipelineId", execution(id).path("pipelineId").asText()));
                        savepoints.add(response);
                    } else if (post && path.endsWith("/rebalance")) {
                        update("UPDATE executions SET generation=generation+1,updated_at=now() WHERE id=?", id);
                        response = execution(id);
                    } else if (!post && parts.length == 4) response = execution(id);
                    else throw new IllegalArgumentException("Unexpected fixture endpoint: " + path);
                } else if (!post && path.equals("/coord/savepoints")) {
                    response = Json.MAPPER.valueToTree(savepoints);
                } else if (!post && path.equals("/coord/state")) {
                    ObjectNode state = Json.MAPPER.createObjectNode();
                    List<String> active = rows(postgres, "SELECT id FROM executions WHERE state IN ('RUNNING','RECOVERING') ORDER BY created_at DESC LIMIT 1");
                    state.set("execution", active.isEmpty() ? Json.MAPPER.nullNode() : execution(active.get(0)));
                    state.putArray("workers");
                    response = state.put("sinkDelayMs", 0);
                } else if (!post && (path.equals("/coord/health") || path.equals("/health"))) {
                    response = Json.MAPPER.valueToTree(Map.of("status", "UP"));
                } else if (!post && path.equals("/state")) {
                    response = Json.MAPPER.createObjectNode().put("simTime", 0).set("trucks", Json.MAPPER.createArrayNode());
                } else throw new IllegalArgumentException("Unexpected fixture endpoint: " + path);
                send(exchange, 200, response);
            } catch (Exception failure) {
                failures.add(failure);
                send(exchange, 500, Json.MAPPER.valueToTree(Map.of("error", "FIXTURE_ERROR", "message", failure.toString())));
            } finally {
                exchange.close();
            }
        }

        private JsonNode insertExecution(JsonNode request) throws Exception {
            String id = request.path("executionId").asText();
            JsonNode plan = request.path("plan");
            try (Connection connection = connection(postgres)) {
                connection.setAutoCommit(false);
                try (var insert = connection.prepareStatement("""
                        INSERT INTO executions(id,pipeline_id,pipeline_version,plan,state,generation,restored_from_checkpoint)
                        VALUES (?,?,?,?::jsonb,'RUNNING',1,?)
                        """)) {
                    insert.setString(1, id);
                    insert.setString(2, plan.path("pipelineId").asText());
                    insert.setInt(3, plan.path("pipelineVersion").asInt());
                    insert.setString(4, plan.toString());
                    if (request.hasNonNull("fromSavepoint")) insert.setLong(5, 0);
                    else insert.setNull(5, java.sql.Types.BIGINT);
                    if (insert.executeUpdate() != 1) throw new IllegalStateException("fixture execution insert failed");
                }
                try (var checkpoint = connection.prepareStatement("""
                        INSERT INTO checkpoints(execution_id,checkpoint_id,generation,state,manifest_key,source_offsets,completed_at,duration_ms)
                        VALUES (?,0,1,'COMPLETED','fixture/manifest.json','{"0":0}'::jsonb,now(),0)
                        """)) {
                    checkpoint.setString(1, id);
                    checkpoint.executeUpdate();
                }
                connection.commit();
            }
            // Deliberately omit org_id above: the real API must attach ownership after this insert.
            return execution(id);
        }

        private JsonNode execution(String id) throws Exception {
            try (Connection connection = connection(postgres); var query = connection.prepareStatement("SELECT * FROM executions WHERE id=?")) {
                query.setString(1, id);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("No fixture execution " + id);
                    ObjectNode execution = Json.MAPPER.createObjectNode().put("id", id)
                            .put("pipelineId", rows.getString("pipeline_id")).put("pipelineVersion", rows.getInt("pipeline_version"))
                            .put("state", rows.getString("state")).put("generation", rows.getLong("generation"))
                            .put("lastCompletedCheckpoint", 0).put("createdAt", rows.getTimestamp("created_at").toInstant().toString());
                    Number restored = (Number) rows.getObject("restored_from_checkpoint");
                    if (restored == null) execution.putNull("restoredFromCheckpoint");
                    else execution.put("restoredFromCheckpoint", restored.longValue());
                    return execution;
                }
            }
        }

        private void update(String sql, String id) throws Exception {
            try (Connection connection = connection(postgres); var update = connection.prepareStatement(sql)) {
                update.setString(1, id);
                if (update.executeUpdate() != 1) throw new IllegalArgumentException("No fixture execution " + id);
            }
        }

        private static void send(HttpExchange exchange, int status, JsonNode body) throws IOException {
            byte[] bytes = Json.MAPPER.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
