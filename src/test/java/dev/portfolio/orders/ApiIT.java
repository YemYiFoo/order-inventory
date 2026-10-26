package dev.portfolio.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Exercises HTTP and committed database state using two packaged JVM processes. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiIT {

    final MySQLContainer mysql =
            new MySQLContainer("mysql:8.4.6")
                    .withDatabaseName("oi_acceptance")
                    .withUsername("oi_test")
                    .withPassword("test-only");

    final JsonMapper json = JsonMapper.builder().build();

    HttpClient client;

    JdbcTemplate sql;

    Process first, second;

    final List<Thread> logReaders = new ArrayList<>();

    int portA, portB;

    final String singleItemRequest = "{\"items\":[{\"productId\":101,\"quantity\":1}]}";

    final String twoItemRequest =
            "{\"items\":[{\"productId\":101,\"quantity\":2},{\"productId\":102,\"quantity\":1}]}";

    @BeforeAll
    void start() throws Exception {
        try {
            mysql.start();
            sql =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            portA = freePort();
            portB = freePort();
            first = launch(portA, "a");
            awaitReady(portA, first);
            second = launch(portB, "b");
            awaitReady(portB, second);
            System.out.println(
                    "TWO_JVMS pid="
                            + first.pid()
                            + ","
                            + second.pid()
                            + " ports="
                            + portA
                            + ","
                            + portB
                            + " schema=oi_acceptance");
        } catch (Exception | AssertionError e) {
            stop();
            throw e;
        }
    }

    Process launch(int port, String name) throws Exception {
        String javaExecutable =
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var builder =
                new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", javaExecutable).toString(),
                        "-jar",
                        "target/order-inventory.jar",
                        "--spring.profiles.active=local",
                        "--server.port=" + port);
        builder.environment()
                .put(
                        "DB_URL",
                        mysql.getJdbcUrl()
                                + (mysql.getJdbcUrl().contains("?") ? "&" : "?")
                                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        builder.environment().put("DB_USER", mysql.getUsername());
        builder.environment().put("DB_PASSWORD", mysql.getPassword());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        logReaders.add(
                Thread.ofPlatform()
                        .daemon()
                        .name("child-log-" + name)
                        .start(
                                () -> {
                                    try (var input = process.getInputStream();
                                            var output =
                                                    Files.newOutputStream(
                                                            Path.of(
                                                                    "target",
                                                                    "app-" + name + ".log"))) {
                                        byte[] buffer = new byte[4096];
                                        int remaining = 131072, length;
                                        while ((length = input.read(buffer)) != -1) {
                                            int keep = Math.min(remaining, length);
                                            output.write(buffer, 0, keep);
                                            remaining -= keep;
                                        }
                                    } catch (IOException e) {
                                        System.err.println(
                                                "Child log drain ended: "
                                                        + e.getClass().getSimpleName());
                                    }
                                }));
        return process;
    }

    void awaitReady(int port, Process process) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < end && process.isAlive()) {
            try {
                if (call(port, "GET", "/actuator/health", null, null, null).statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
            }
            Thread.sleep(150);
        }
        fail("App did not become healthy; see target/app-*.log");
    }

    static int freePort() throws Exception {
        try (var s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    @BeforeEach
    void reset() {
        assertEquals("oi_acceptance", sql.queryForObject("SELECT DATABASE()", String.class));
        sql.update("DELETE FROM order_items");
        sql.update("DELETE FROM orders");
        sql.update("UPDATE products SET available_stock=100");
    }

    @AfterAll
    void stop() throws Exception {
        for (Process process : new Process[] {first, second}) {
            if (process != null) {
                process.destroy();
                if (!process.waitFor(8, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }
        }
        for (Thread reader : logReaders) {
            reader.join(2000);

            assertFalse(reader.isAlive(), "Child log reader survived cleanup");
        }
        if (client != null) {
            client.close();
        }
        mysql.stop();
    }

    HttpResponse<String> call(
            int port, String method, String path, String user, String key, String body)
            throws Exception {
        var b =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(20));
        if (user != null) {
            b.header(
                    "Authorization",
                    "Basic "
                            + Base64.getEncoder()
                                    .encodeToString(
                                            (user + ":Demo-only-123!")
                                                    .getBytes(StandardCharsets.UTF_8)));
        }
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> place(String key, String body) throws Exception {
        return call(portA, "POST", "/api/orders", "customer-a", key, body);
    }

    JsonNode tree(HttpResponse<String> r) {
        return json.readTree(r.body());
    }

    long id(HttpResponse<String> r) {
        return tree(r).get("id").longValue();
    }

    int stock(int id) {
        return sql.queryForObject(
                "SELECT available_stock FROM products WHERE id=?", Integer.class, id);
    }

    long count(String table) {
        return sql.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    void catalogueHealthAndPlacement() throws Exception {
        assertEquals(200, call(portA, "GET", "/actuator/health", null, null, null).statusCode());
        assertEquals(
                5, tree(call(portA, "GET", "/api/products", null, null, null)).get("items").size());
        assertEquals(404, call(portA, "GET", "/api/products/999", null, null, null).statusCode());
        var placed = place("normal", twoItemRequest);

        assertEquals(201, placed.statusCode(), placed.body());
        assertEquals(
                "/api/orders/" + id(placed), placed.headers().firstValue("Location").orElseThrow());
        assertEquals("28.20", tree(placed).get("totalAmount").asText());
        assertEquals("PLACED", tree(placed).get("status").asText());
        assertEquals(2, tree(placed).get("items").size());
        assertEquals(98, stock(101));
        assertEquals(99, stock(102));
        assertEquals(1L, sql.queryForObject("SELECT customer_id FROM orders", Long.class));
        assertEquals(2, count("order_items"));
        assertInvariants(100);
    }

    void assertInvariants(int initial) {
        for (int p = 101; p <= 105; p++) {
            long used =
                    sql.queryForObject(
                            "SELECT COALESCE(SUM(i.quantity),0) FROM order_items i JOIN orders o ON"
                                    + " o.id=i.order_id WHERE i.product_id=? AND o.status IN"
                                    + " ('PLACED','SHIPPED')",
                            Long.class,
                            p);
            assertEquals(initial, stock(p) + used, "product " + p);
        }
        assertEquals(
                0L,
                sql.queryForObject(
                        "SELECT COUNT(*) FROM orders o WHERE o.total_amount != (SELECT"
                                + " COALESCE(SUM(i.line_total),0) FROM order_items i WHERE"
                                + " i.order_id=o.id)",
                        Long.class));
    }

    @Test
    void laterFailureRollsBackEverythingAndKeyCanBeReused() throws Exception {
        sql.update("UPDATE products SET available_stock=0 WHERE id=102");

        assertEquals(409, place("rollback", twoItemRequest).statusCode());
        assertEquals(100, stock(101));
        assertEquals(0, stock(102));
        assertEquals(0, count("orders"));
        assertEquals(0, count("order_items"));
        String missing = twoItemRequest.replace("102", "999");

        assertEquals(404, place("rollback", missing).statusCode());
        assertEquals(100, stock(101));
        assertEquals(0, count("orders"));
        assertEquals(0, count("order_items"));
        assertEquals(201, place("rollback", singleItemRequest).statusCode());
    }

    @Test
    void inputRejectionDoesNotWrite() throws Exception {
        var bad =
                new ArrayList<>(
                        List.of(
                                "null",
                                "[]",
                                "{}",
                                "{\"items\":null}",
                                "{\"items\":[]}",
                                "{\"items\":[],\"items\":[]}",
                                singleItemRequest + " {}",
                                singleItemRequest.replace("quantity", "unexpected"),
                                singleItemRequest.replace("1}", "1,\"quantity\":1}"),
                                singleItemRequest.replace("101", "9223372036854775808"),
                                singleItemRequest.replace("101", "101.0"),
                                singleItemRequest.replace("101", "\"101\""),
                                singleItemRequest.replace("101", "0"),
                                singleItemRequest.replace(":1}", ":null}"),
                                singleItemRequest.replace(":1}", ":\"1\"}"),
                                singleItemRequest.replace(":1}", ":1.5}"),
                                singleItemRequest.replace(":1}", ":2147483648}"),
                                singleItemRequest.replace(":1}", ":0}"),
                                singleItemRequest.replace(":1}", ":-1}"),
                                singleItemRequest.replace(":1}", ":1001}"),
                                "{\"items\":[{\"productId\":101,\"quantity\":1},{\"productId\":101,\"quantity\":1}]}",
                                singleItemRequest.replace("}]}", "}],\"price\":0}")));
        bad.add(
                "{\"items\":["
                        + String.join(
                                ",", Collections.nCopies(21, "{\"productId\":101,\"quantity\":1}"))
                        + "]}");
        for (String body : bad) {
            assertEquals(400, place("invalid", body).statusCode(), body);
        }
        for (String key : new String[] {null, "", "a b", "a,b", "x".repeat(129)}) {
            assertEquals(400, place(key, singleItemRequest).statusCode());
        }
        var auth =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        "customer-a:Demo-only-123!"
                                                .getBytes(StandardCharsets.UTF_8));
        var b =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + portA + "/api/orders"))
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "a")
                        .header("Idempotency-Key", "b")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(singleItemRequest));
        assertEquals(
                400, client.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        for (String[] headers :
                new String[][] {{"Content-Type", "text/plain"}, {"Content-Encoding", "gzip"}}) {
            var request =
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + portA + "/api/orders"))
                            .header("Authorization", auth)
                            .header("Idempotency-Key", "media")
                            .header(headers[0], headers[1]);
            if (!headers[0].equals("Content-Type")) {
                request.header("Content-Type", "application/json");
            }
            assertEquals(
                    415,
                    client.send(
                                    request.POST(
                                                    HttpRequest.BodyPublishers.ofString(
                                                            singleItemRequest))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .statusCode());
        }
        String large = " ".repeat(65537);

        assertEquals(413, place("large", large).statusCode());
        // Unknown length publisher forces streamed/chunked HTTP/1.1 rather than relying on
        // Content-Length.
        var chunked =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + portA + "/api/orders"))
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "chunked")
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofInputStream(
                                        () ->
                                                new ByteArrayInputStream(
                                                        large.getBytes(StandardCharsets.UTF_8))))
                        .build();
        assertEquals(413, client.send(chunked, HttpResponse.BodyHandlers.ofString()).statusCode());
        for (String path :
                List.of(
                        "/api/products/0",
                        "/api/products/-1",
                        "/api/products/no",
                        "/api/products/9223372036854775808",
                        "/api/orders?page=-1",
                        "/api/orders?page=10001",
                        "/api/orders?size=0",
                        "/api/orders?size=101",
                        "/api/orders?page=x")) {
            assertEquals(
                    400, call(portA, "GET", path, "customer-a", null, null).statusCode(), path);
        }
        assertEquals(0, count("orders"));
        assertEquals(0, count("order_items"));
        assertEquals(100, stock(101));
        long id = id(place("bodyless", singleItemRequest));

        assertEquals(
                400,
                call(portA, "POST", "/api/orders/" + id + "/cancel", "customer-a", null, "{}")
                        .statusCode());
        assertEquals(
                400,
                call(portA, "POST", "/api/admin/orders/" + id + "/ship", "admin", null, " ")
                        .statusCode());
        assertEquals(
                "PLACED",
                sql.queryForObject("SELECT status FROM orders WHERE id=?", String.class, id));
        assertEquals(
                200,
                call(portA, "POST", "/api/orders/" + id + "/cancel", "customer-a", null, null)
                        .statusCode());
    }

    @Test
    void ownershipRolesAndSafeErrors() throws Exception {
        assertEquals(
                401,
                call(portA, "POST", "/api/orders", null, "auth", singleItemRequest).statusCode());
        assertEquals(401, call(portA, "GET", "/api/orders", "wrong-user", null, null).statusCode());
        assertEquals(
                403,
                call(portA, "POST", "/api/orders", "admin", "auth", singleItemRequest)
                        .statusCode());
        long id = id(place("owner", singleItemRequest));
        for (String method : List.of("GET", "POST")) {
            String path = "/api/orders/" + id + (method.equals("POST") ? "/cancel" : "");
            var response = call(portA, method, path, "customer-b", null, null);

            assertEquals(404, response.statusCode());
            assertEquals("NOT_FOUND", tree(response).get("code").asText());
        }
        assertEquals(
                403,
                call(portA, "POST", "/api/admin/orders/" + id + "/ship", "customer-a", null, null)
                        .statusCode());
        assertEquals(99, stock(101));
        assertEquals("PLACED", sql.queryForObject("SELECT status FROM orders", String.class));
        assertEquals(
                200,
                call(portA, "POST", "/api/admin/orders/" + id + "/ship", "admin", null, null)
                        .statusCode());
        assertEquals(
                404,
                call(portA, "POST", "/api/admin/orders/999999/ship", "admin", null, null)
                        .statusCode());
        assertEquals(
                403, call(portA, "GET", "/actuator/env", "customer-a", null, null).statusCode());
    }

    @Test
    void frameworkRoutingErrorsRemainClientErrors() throws Exception {
        var unsupported = call(portA, "DELETE", "/api/orders", "customer-a", null, null);

        assertEquals(405, unsupported.statusCode(), unsupported.body());
        assertEquals("METHOD_NOT_ALLOWED", tree(unsupported).get("code").asText());
        String allow = unsupported.headers().firstValue("Allow").orElseThrow();

        assertTrue(allow.contains("GET") && allow.contains("POST"), allow);
        var missing = call(portB, "GET", "/api/orders/1/not-a-route", "customer-a", null, null);

        assertEquals(404, missing.statusCode(), missing.body());
        assertEquals("NOT_FOUND", tree(missing).get("code").asText());
        var negotiation =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + portA + "/api/products"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Accept", "text/plain")
                        .GET()
                        .build();
        var unacceptable = client.send(negotiation, HttpResponse.BodyHandlers.ofString());

        assertEquals(406, unacceptable.statusCode(), unacceptable.body());
        assertEquals("NOT_ACCEPTABLE", tree(unacceptable).get("code").asText());
        var invalid =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + portA + "/api/products/0"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Accept", "text/plain")
                        .GET()
                        .build();
        var invalidResponse = client.send(invalid, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, invalidResponse.statusCode(), invalidResponse.body());
        assertEquals("INVALID_REQUEST", tree(invalidResponse).get("code").asText());
        assertEquals(0, count("orders"));
        assertEquals(0, count("order_items"));
        assertEquals(100, stock(101));
    }

    @Test
    void unacceptableResponseFormatDoesNotCommitWrites() throws Exception {
        for (String operation : List.of("place", "cancel", "ship")) {
            reset();
            boolean placing = operation.equals("place");
            long order = placing ? 0 : id(place("existing", singleItemRequest));
            String path =
                    placing
                            ? "/api/orders"
                            : operation.equals("cancel")
                                    ? "/api/orders/" + order + "/cancel"
                                    : "/api/admin/orders/" + order + "/ship";
            String user = operation.equals("ship") ? "admin" : "customer-a";
            String auth =
                    "Basic "
                            + Base64.getEncoder()
                                    .encodeToString(
                                            (user + ":Demo-only-123!")
                                                    .getBytes(StandardCharsets.UTF_8));
            var request =
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + portA + path))
                            .timeout(Duration.ofSeconds(20))
                            .header("Authorization", auth)
                            .header("Accept", "text/plain");
            if (placing) {
                request.header("Idempotency-Key", "rejected-format")
                        .header("Content-Type", "application/json");
            }
            var response =
                    client.send(
                            request.POST(
                                            placing
                                                    ? HttpRequest.BodyPublishers.ofString(
                                                            singleItemRequest)
                                                    : HttpRequest.BodyPublishers.noBody())
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(406, response.statusCode(), operation + ": " + response.body());
            assertEquals("NOT_ACCEPTABLE", tree(response).get("code").asText());
            assertEquals(
                    placing ? 0 : 1, count("orders"), operation + " must not commit a new order");
            assertEquals(placing ? 0 : 1, count("order_items"));
            assertEquals(placing ? 100 : 99, stock(101), operation + " must not change stock");
            if (placing) {
                assertEquals(
                        201,
                        place("rejected-format", singleItemRequest).statusCode(),
                        "Rejected negotiation must not reserve the key");
            } else {
                assertEquals(
                        "PLACED",
                        sql.queryForObject(
                                "SELECT status FROM orders WHERE id=?", String.class, order));
            }
            assertInvariants(100);
        }
    }

    @Test
    void everyContentEncodingFieldIsValidatedBeforeWriting() throws Exception {
        String auth =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        "customer-a:Demo-only-123!"
                                                .getBytes(StandardCharsets.UTF_8));
        for (var encodings :
                List.of(
                        List.of("identity", "gzip"),
                        List.of("gzip", "identity"),
                        List.of("identity, gzip"),
                        List.of("identity,,gzip,"))) {
            var request =
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + portA + "/api/orders"))
                            .header("Authorization", auth)
                            .header("Idempotency-Key", "encoding-list")
                            .header("Content-Type", "application/json");
            for (String encoding : encodings) {
                request.header("Content-Encoding", encoding);
            }
            var response =
                    client.send(
                            request.POST(HttpRequest.BodyPublishers.ofString(singleItemRequest))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(415, response.statusCode(), response.body());
            assertEquals(0, count("orders"));
            assertEquals(0, count("order_items"));
            assertEquals(100, stock(101));
        }
        int expected = 201;
        for (var encodings :
                List.of(
                        List.of("identity, IDENTITY", "identity"),
                        List.of("identity,,"),
                        List.of(""))) {
            var accepted =
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + portA + "/api/orders"))
                            .header("Authorization", auth)
                            .header("Idempotency-Key", "encoding-list")
                            .header("Content-Type", "application/json");
            for (String encoding : encodings) {
                accepted.header("Content-Encoding", encoding);
            }
            assertEquals(
                    expected,
                    client.send(
                                    accepted.POST(
                                                    HttpRequest.BodyPublishers.ofString(
                                                            singleItemRequest))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .statusCode());
            expected = 200;
        }
        assertInvariants(100);
    }

    @Test
    void maximumBodyAndRealUnrelatedUniqueFailure() throws Exception {
        String padded =
                singleItemRequest
                        + " "
                                .repeat(
                                        65536
                                                - singleItemRequest.getBytes(StandardCharsets.UTF_8)
                                                        .length);
        assertEquals(201, place("maximum-body", padded).statusCode());
        assertEquals(413, place("too-large", padded + " ").statusCode());
        // A real, unrelated MySQL 1062 must escape the facade, not become a replay or 404.
        sql.execute("CREATE UNIQUE INDEX review_unique_canonical ON orders(canonical_request)");
        try {
            var response = place("different-key", singleItemRequest);

            assertEquals(500, response.statusCode(), response.body());
            assertEquals("UNEXPECTED_ERROR", tree(response).get("code").asText());
            assertEquals(1, count("orders"));
            assertEquals(1, count("order_items"));
            assertEquals(99, stock(101));
            assertFalse(response.body().contains("review_unique_canonical"));
        } finally {
            sql.execute("DROP INDEX review_unique_canonical ON orders");
        }
        assertEquals(201, place("different-key", singleItemRequest).statusCode());
        assertInvariants(100);
    }

    @Test
    void replaysCanonicalScopeCaseAndTerminalState() throws Exception {
        var original = place("same", twoItemRequest);

        assertEquals(201, original.statusCode());
        String reordered =
                " { \"items\" :"
                    + " [{\"quantity\":1,\"productId\":102},{\"quantity\":2,\"productId\":101}] } ";
        var replay = place("same", reordered);

        assertEquals(200, replay.statusCode());
        assertEquals(id(original), id(replay));
        assertEquals(
                original.headers().firstValue("Location"), replay.headers().firstValue("Location"));
        var conflict = place("same", singleItemRequest);

        assertEquals(409, conflict.statusCode());
        assertEquals("IDEMPOTENCY_CONFLICT", tree(conflict).get("code").asText());
        assertEquals(1, count("orders"));
        assertEquals(98, stock(101));
        var other = call(portB, "POST", "/api/orders", "customer-b", "same", singleItemRequest);

        assertEquals(201, other.statusCode());
        assertNotEquals(id(original), id(other));
        assertEquals(201, place("Same", singleItemRequest).statusCode());
        call(portA, "POST", "/api/orders/" + id(original) + "/cancel", "customer-a", null, null);
        var cancelled = place("same", twoItemRequest);

        assertEquals(200, cancelled.statusCode());
        assertEquals("CANCELLED", tree(cancelled).get("status").asText());
        assertEquals(98, stock(101));
        assertInvariants(100);
    }

    @Test
    void terminalTransitionsRestoreOnce() throws Exception {
        long cancel = id(place("cancel", twoItemRequest));
        for (int n = 0; n < 3; n++) {
            assertEquals(
                    200,
                    call(
                                    portA,
                                    "POST",
                                    "/api/orders/" + cancel + "/cancel",
                                    "customer-a",
                                    null,
                                    null)
                            .statusCode());
        }
        assertEquals(100, stock(101));
        assertEquals(100, stock(102));
        assertEquals(
                409,
                call(portA, "POST", "/api/admin/orders/" + cancel + "/ship", "admin", null, null)
                        .statusCode());
        long ship = id(place("ship", twoItemRequest));
        for (int n = 0; n < 3; n++) {
            assertEquals(
                    200,
                    call(portA, "POST", "/api/admin/orders/" + ship + "/ship", "admin", null, null)
                            .statusCode());
        }
        assertEquals(98, stock(101));
        assertEquals(99, stock(102));
        assertEquals(
                409,
                call(portA, "POST", "/api/orders/" + ship + "/cancel", "customer-a", null, null)
                        .statusCode());
        assertInvariants(100);
    }

    interface Contender {

        HttpResponse<String> run(int number) throws Exception;
    }

    List<HttpResponse<String>> race(int n, Contender task) throws Exception {
        var ready = new CountDownLatch(n);
        var go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(n);
        try {
            var futures = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < n; i++) {
                final int index = i;
                futures.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();

                                    assertTrue(go.await(10, TimeUnit.SECONDS));
                                    return task.run(index);
                                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            var results = new ArrayList<HttpResponse<String>>();
            for (var f : futures) {
                results.add(f.get(35, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            go.countDown();
            pool.shutdownNow();

            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    int port(int n) {
        return n % 2 == 0 ? portA : portB;
    }

    @Test
    void sameKeyRaceAcrossTwoProcessesThreeRuns() throws Exception {
        for (int run = 0; run < 3; run++) {
            reset();
            var results =
                    race(
                            16,
                            n ->
                                    call(
                                            port(n),
                                            "POST",
                                            "/api/orders",
                                            "customer-a",
                                            "race",
                                            twoItemRequest));
            assertEquals(1, results.stream().filter(r -> r.statusCode() == 201).count());
            assertEquals(
                    15,
                    results.stream().filter(r -> r.statusCode() == 200).count(),
                    results.toString());
            assertEquals(1, results.stream().map(this::id).distinct().count());
            assertEquals(
                    1,
                    results.stream()
                            .map(r -> r.headers().firstValue("Location").orElseThrow())
                            .distinct()
                            .count());
            assertEquals(1, count("orders"));
            assertEquals(2, count("order_items"));
            assertEquals(98, stock(101));
            assertEquals(99, stock(102));
            assertInvariants(100);
        }
    }

    @Test
    void differentContentsRaceMatchesWinner() throws Exception {
        for (int run = 0; run < 3; run++) {
            reset();
            var results =
                    race(
                            2,
                            n ->
                                    call(
                                            port(n),
                                            "POST",
                                            "/api/orders",
                                            "customer-a",
                                            "different",
                                            n == 0 ? singleItemRequest : twoItemRequest));
            assertEquals(
                    List.of(201, 409),
                    results.stream().map(HttpResponse::statusCode).sorted().toList());
            var winner =
                    results.stream().filter(r -> r.statusCode() == 201).findFirst().orElseThrow();
            int quantity = tree(winner).get("items").get(0).get("quantity").intValue();

            assertEquals(100 - quantity, stock(101));
            assertEquals(1, count("orders"));
            assertInvariants(100);
        }
    }

    @Test
    void stockAndOverlappingMultiItemRacesThreeRuns() throws Exception {
        for (int run = 0; run < 3; run++) {
            for (String body : List.of(singleItemRequest, twoItemRequest)) {
                reset();
                sql.update("UPDATE products SET available_stock=5");
                var results =
                        race(
                                16,
                                n ->
                                        call(
                                                port(n),
                                                "POST",
                                                "/api/orders",
                                                "customer-a",
                                                "stock-" + n,
                                                body));
                int successes = body.equals(singleItemRequest) ? 5 : 2;

                assertEquals(
                        successes,
                        results.stream().filter(r -> r.statusCode() == 201).count(),
                        results.toString());
                assertEquals(
                        16 - successes,
                        results.stream()
                                .filter(
                                        r ->
                                                r.statusCode() == 409
                                                        && tree(r).get("code")
                                                                .asText()
                                                                .equals("INSUFFICIENT_STOCK"))
                                .count());
                assertEquals(successes, count("orders"));
                assertEquals(body.equals(singleItemRequest) ? 0 : 1, stock(101));
                assertInvariants(5);
            }
        }
    }

    @Test
    void concurrentCancelAndShipmentSerializeAcrossProcesses() throws Exception {
        for (int run = 0; run < 3; run++) {
            reset();
            long id = id(place("cancel-race", twoItemRequest));
            var cancelled =
                    race(
                            8,
                            n ->
                                    call(
                                            port(n),
                                            "POST",
                                            "/api/orders/" + id + "/cancel",
                                            "customer-a",
                                            null,
                                            null));
            assertTrue(
                    cancelled.stream()
                            .allMatch(
                                    r ->
                                            r.statusCode() == 200
                                                    && id(r) == id
                                                    && tree(r).get("status")
                                                            .asText()
                                                            .equals("CANCELLED")));
            assertEquals(100, stock(101));
            assertInvariants(100);
            long contested = id(place("transition-race", twoItemRequest));
            var results =
                    race(
                            2,
                            n ->
                                    call(
                                            port(n),
                                            "POST",
                                            n == 0
                                                    ? "/api/orders/" + contested + "/cancel"
                                                    : "/api/admin/orders/" + contested + "/ship",
                                            n == 0 ? "customer-a" : "admin",
                                            null,
                                            null));
            assertEquals(
                    List.of(200, 409),
                    results.stream().map(HttpResponse::statusCode).sorted().toList());
            String state =
                    sql.queryForObject(
                            "SELECT status FROM orders WHERE id=?", String.class, contested);
            assertTrue(List.of("CANCELLED", "SHIPPED").contains(state));
            assertEquals(state.equals("CANCELLED") ? 100 : 98, stock(101));
            assertInvariants(100);
        }
    }

    @Test
    void lockTimeoutRollsBackEarlierWorkAndRetrySucceeds() throws Exception {
        try (var lock =
                DriverManager.getConnection(
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            lock.setAutoCommit(false);
            try (var s = lock.createStatement()) {
                s.executeQuery("SELECT id FROM products WHERE id=102 FOR UPDATE").close();
            }
            var response = place("lock-timeout", twoItemRequest);

            assertEquals(503, response.statusCode(), response.body());
            assertEquals("TEMPORARY_DATABASE_FAILURE", tree(response).get("code").asText());
            assertEquals("1", response.headers().firstValue("Retry-After").orElseThrow());
            assertEquals(100, stock(101));
            assertEquals(0, count("orders"));
            assertEquals(0, count("order_items"));
            lock.rollback();
        }
        assertEquals(201, place("lock-timeout", twoItemRequest).statusCode());
        assertInvariants(100);
    }

    @Test
    void failedUncommittedOwnerWithTwoWaitersConverges() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try (var owner =
                DriverManager.getConnection(
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            owner.setAutoCommit(false);
            try (var s =
                    owner.prepareStatement(
                            "INSERT INTO"
                                + " orders(customer_id,idempotency_key,canonical_request,status,currency,total_amount)"
                                + " VALUES(1,'owner-rollback','v1|101:1','PLACED','MYR',0)")) {
                s.executeUpdate();
            }
            var f1 =
                    pool.submit(
                            () ->
                                    call(
                                            portA,
                                            "POST",
                                            "/api/orders",
                                            "customer-a",
                                            "owner-rollback",
                                            singleItemRequest));
            var f2 =
                    pool.submit(
                            () ->
                                    call(
                                            portB,
                                            "POST",
                                            "/api/orders",
                                            "customer-a",
                                            "owner-rollback",
                                            singleItemRequest));
            long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
            boolean waiting = false;
            while (System.nanoTime() < deadline) {
                long inserts =
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE INFO"
                                        + " LIKE 'INSERT INTO orders%'",
                                Long.class);
                if (inserts >= 2) {
                    waiting = true;
                    break;
                }
                Thread.sleep(25);
            }
            assertTrue(waiting, "Two actual INSERT contenders must be waiting before rollback");
            owner.rollback();
            int created = 0;
            for (var f : List.of(f1, f2)) {
                var response = f.get(20, TimeUnit.SECONDS);
                if (response.statusCode() == 503) {
                    assertEquals("TEMPORARY_DATABASE_FAILURE", tree(response).get("code").asText());
                    response = place("owner-rollback", singleItemRequest);
                }
                assertTrue(
                        response.statusCode() == 200 || response.statusCode() == 201,
                        response.body());
                if (response.statusCode() == 201) {
                    created++;
                }
            }
            assertEquals(1, created);
            assertEquals(1, count("orders"));
            assertEquals(1, count("order_items"));
            assertEquals(99, stock(101));
            assertInvariants(100);
        } finally {
            pool.shutdownNow();

            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationLockTimeoutRollsBackEarlierRestoration() throws Exception {
        long order = id(place("cancel-timeout", twoItemRequest));
        try (var lock =
                DriverManager.getConnection(
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            lock.setAutoCommit(false);
            try (var statement = lock.createStatement()) {
                statement.executeQuery("SELECT id FROM products WHERE id=102 FOR UPDATE").close();
            }
            var response =
                    call(
                            portA,
                            "POST",
                            "/api/orders/" + order + "/cancel",
                            "customer-a",
                            null,
                            null);
            assertEquals(503, response.statusCode(), response.body());
            assertEquals("TEMPORARY_DATABASE_FAILURE", tree(response).get("code").asText());
            assertEquals("1", response.headers().firstValue("Retry-After").orElseThrow());
            assertEquals(
                    "PLACED",
                    sql.queryForObject(
                            "SELECT status FROM orders WHERE id=?", String.class, order));
            assertEquals(98, stock(101));
            assertEquals(99, stock(102));
            assertInvariants(100);
            lock.rollback();
        }
        assertEquals(
                200,
                call(portB, "POST", "/api/orders/" + order + "/cancel", "customer-a", null, null)
                        .statusCode());
        assertEquals(100, stock(101));
        assertEquals(100, stock(102));
        assertInvariants(100);
    }

    @Test
    void historyHasStablePagesAndImmutableSnapshots() throws Exception {
        long a = id(place("history-1", singleItemRequest)),
                b = id(place("history-2", twoItemRequest));
        call(portA, "POST", "/api/orders", "customer-b", "history-b", singleItemRequest);
        sql.update("UPDATE orders SET created_at='2026-01-01 00:00:00.123456'");
        var firstPage =
                tree(call(portA, "GET", "/api/orders?page=0&size=1", "customer-a", null, null));
        assertEquals(2, firstPage.get("totalElements").intValue());
        assertEquals(b, firstPage.get("items").get(0).get("id").longValue());
        var next = tree(call(portA, "GET", "/api/orders?page=1&size=1", "customer-a", null, null));

        assertEquals(a, next.get("items").get(0).get("id").longValue());
        assertTrue(
                tree(call(portA, "GET", "/api/orders?page=10000", "customer-a", null, null))
                        .get("items")
                        .isEmpty());
        assertEquals(
                1,
                tree(call(portA, "GET", "/api/orders", "customer-b", null, null))
                        .get("totalElements")
                        .intValue());
        String oldName = sql.queryForObject("SELECT name FROM products WHERE id=101", String.class);
        try {
            sql.update("UPDATE products SET price=99.99,name='Changed',sku='CHANGED' WHERE id=101");
            var item =
                    tree(call(portA, "GET", "/api/orders/" + a, "customer-a", null, null))
                            .get("items")
                            .get(0);
            assertEquals("12.50", item.get("unitPrice").asText());
            assertEquals(oldName, item.get("name").asText());
            assertEquals("NOTE-A5", item.get("sku").asText());
        } finally {
            sql.update(
                    "UPDATE products SET price=12.50,name=?,sku='NOTE-A5' WHERE id=101", oldName);
        }
    }

    @Test
    void discardedResponseAndRestartReplayPreserveSeedAndInventory() throws Exception {
        // Intentionally discard response content. Inspect committed state through an independent
        // SQL connection.
        call(portA, "POST", "/api/orders", "customer-a", "lost-response", twoItemRequest);
        long committed =
                sql.queryForObject(
                        "SELECT id FROM orders WHERE idempotency_key='lost-response'", Long.class);
        assertEquals(98, stock(101));
        assertEquals(committed, id(place("lost-response", twoItemRequest)));
        assertEquals(1, count("orders"));
        first.destroy();

        assertTrue(first.waitFor(10, TimeUnit.SECONDS));
        first = launch(portA, "a-restarted");
        awaitReady(portA, first);
        var replay = place("lost-response", twoItemRequest);

        assertEquals(200, replay.statusCode());
        assertEquals(committed, id(replay));
        assertEquals(98, stock(101));
        assertEquals(3, count("app_users"));
        assertEquals(5, count("products"));
        assertEquals(
                2L,
                sql.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Long.class));
        call(portB, "POST", "/api/orders/" + committed + "/cancel", "customer-a", null, null);

        assertEquals(
                "CANCELLED", tree(place("lost-response", twoItemRequest)).get("status").asText());
        assertInvariants(100);
    }

    @Test
    void constraintsRejectInvalidRowsAndUnexpectedIntegrityIsNotReplay() {
        var check =
                assertThrows(
                        DataAccessException.class,
                        () -> sql.update("UPDATE products SET available_stock=-1 WHERE id=101"));
        assertEquals(3819, ((SQLException) check.getMostSpecificCause()).getErrorCode());
        assertEquals(100, stock(101));
        var foreignKey =
                assertThrows(
                        DataAccessException.class,
                        () ->
                                sql.update(
                                        "INSERT INTO"
                                            + " orders(customer_id,idempotency_key,canonical_request,status,currency,total_amount)"
                                            + " VALUES(999,'bad','v1','PLACED','MYR',0)"));
        assertEquals(1452, ((SQLException) foreignKey.getMostSpecificCause()).getErrorCode());
        assertEquals(0, count("orders"));
    }

    @Test
    void historyExplainOnRepresentativeDisposableFixture() throws Exception {
        var args = new ArrayList<Object[]>();
        for (int i = 0; i < 5000; i++) {
            args.add(new Object[] {i % 5 == 0 ? 1 : 2, "plan-" + i});
        }
        sql.batchUpdate(
                "INSERT INTO"
                    + " orders(customer_id,idempotency_key,canonical_request,status,currency,total_amount,created_at)"
                    + " VALUES(?,?,'v1','PLACED','MYR',0,'2026-01-01 00:00:00.123456')",
                args);
        sql.execute("ANALYZE TABLE orders");
        String query =
                "SELECT id,status,currency,total_amount,created_at FROM orders WHERE customer_id=?"
                        + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?";
        assertEquals(20, sql.queryForList(query, 1, 20, 0).size());
        var plan = sql.queryForList("EXPLAIN " + query, 1, 20, 0);

        assertFalse(plan.isEmpty());
        Files.writeString(
                Path.of("target/history-explain.txt"),
                "5000 orders; customer 1:1000, customer 2:4000\n"
                        + query
                        + "\nParameters [1,20,0]\n"
                        + plan
                        + "\n"
                        + sql.queryForList("SHOW INDEX FROM orders"),
                StandardCharsets.UTF_8);
    }

    @Test
    void databaseOutageAffectsHealthWithoutDetails() throws Exception {
        // Pause only this disposable Testcontainers DB, then unpause in finally.
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(mysql.getContainerId()).exec();
        try {
            var response = call(portA, "GET", "/actuator/health", null, null, null);

            assertEquals(503, response.statusCode());
            assertEquals("DOWN", tree(response).get("status").asText());
            for (String field : tree(response).propertyNames()) {
                assertTrue(Set.of("status", "groups").contains(field), response.body());
            }
            assertFalse(
                    response.body()
                            .toLowerCase(Locale.ROOT)
                            .matches("(?s).*(jdbc|password|mysql|exception|oi_acceptance).*"),
                    response.body());
            var catalogue = call(portA, "GET", "/api/products", null, null, null);

            assertEquals(503, catalogue.statusCode(), catalogue.body());
            assertEquals("TEMPORARY_DATABASE_FAILURE", tree(catalogue).get("code").asText());
            var authenticated = call(portA, "GET", "/api/orders", "customer-a", null, null);

            assertEquals(503, authenticated.statusCode(), authenticated.body());
            assertEquals("TEMPORARY_DATABASE_FAILURE", tree(authenticated).get("code").asText());
            assertEquals("1", authenticated.headers().firstValue("Retry-After").orElseThrow());
        } finally {
            docker.unpauseContainerCmd(mysql.getContainerId()).exec();
        }
    }
}
