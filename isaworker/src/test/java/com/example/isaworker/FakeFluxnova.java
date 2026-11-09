package com.example.isaworker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal imitation of the Fluxnova engine's REST API, for any number of tenants, started on a random port: version and
 * the external task calls fetchAndLock / complete / failure. It behaves like the engine for the demo
 * processes: when a task is completed it offers the next task of the same process instance, and it records every completion,
 * failure and poll with the credentials the request carried.
 * <p>
 * Like a real Fluxnova 3.1.1 engine it writes dates with a colon in the zone offset ("+00:00"), which the client library
 * only reads once {@code fluxnova-workers.date-format} is set: the first run against a real engine failed exactly there.
 */
final class FakeFluxnova {

    record Completion(String tenant, String topic, String taskId, String processInstanceId, String authorization, String body) {
    }

    record Failure(String tenant, String topic, String taskId, String body) {
    }


    private record Task(String id, String topic, String processInstanceId, String variablesJson) {
    }

    /** What the engine offers next, once a task of this topic is completed. */
    private static final Map<String, String> NEXT_TOPIC = Map.of(
            "return-name", "return-address",
            "enter-name-topic", "enter-address-topic",
            "enter-address-topic", "show-details-topic");

    private static final Pattern PATH = Pattern.compile("^/engine-rest/engine/([^/]+)/(version"
            + "|external-task/fetchAndLock|external-task/([^/]+)/complete|external-task/([^/]+)/failure)$");

    private final HttpServer server;
    private final Map<String, Queue<Task>> offered = new ConcurrentHashMap<>();     // tenant -> tasks waiting to be fetched
    private final Map<String, Task> tasksById = new ConcurrentHashMap<>();
    private final Queue<Completion> completions = new ConcurrentLinkedQueue<>();
    private final Queue<Failure> failures = new ConcurrentLinkedQueue<>();
    private final Map<String, Queue<String>> fetchBodies = new ConcurrentHashMap<>();
    private final Map<String, String> lastFetchAuthorization = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> fetchCounts = new ConcurrentHashMap<>();
    private final AtomicLong taskSequence = new AtomicLong();
    private volatile boolean rejectCredentials;

    FakeFluxnova() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/engine-rest/engine/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/engine-rest/engine";
    }

    void close() {
        server.stop(0);
    }

    void rejectCredentials() {
        rejectCredentials = true;
    }


    /** A new process instance of the demo process arrives at its first task (topic return-name) for this tenant. */
    void offerNameTask(String tenant) {
        offer(tenant, "return-name", "pi-" + tenant, "{}");
    }

    /**
     * A new instance of the worker details process arrives at its first task (topic enter-name-topic). The process variables
     * the caller supplied when starting it are given as {@code name} and {@code address}; null leaves one out.
     */
    void offerWorkerDetailsProcess(String tenant, String processInstanceId, String name, String address) {
        StringBuilder variables = new StringBuilder("{");
        if (name != null) {
            variables.append("\"name\":{\"type\":\"String\",\"value\":\"").append(name).append("\",\"valueInfo\":{}}");
        }
        if (address != null) {
            variables.append(name != null ? "," : "")
                    .append("\"address\":{\"type\":\"String\",\"value\":\"").append(address).append("\",\"valueInfo\":{}}");
        }
        offer(tenant, "enter-name-topic", processInstanceId, variables.append('}').toString());
    }

    private void offer(String tenant, String topic, String processInstanceId, String variablesJson) {
        Task task = new Task(tenant + "-" + topic + "-" + taskSequence.incrementAndGet(), topic, processInstanceId, variablesJson);
        tasksById.put(task.id(), task);
        offered.computeIfAbsent(tenant, t -> new ConcurrentLinkedQueue<>()).add(task);
    }

    List<Completion> completions() {
        return List.copyOf(completions);
    }

    long completionCount(String tenant, String topic) {
        return completions.stream().filter(c -> c.tenant().equals(tenant) && c.topic().equals(topic)).count();
    }

    Completion completion(String tenant, String topic) {
        return completions.stream().filter(c -> c.tenant().equals(tenant) && c.topic().equals(topic)).findFirst()
                .orElseThrow(() -> new AssertionError("no completion of " + topic + " for " + tenant));
    }

    List<String> completionOrder(String tenant) {
        return completions.stream().filter(c -> c.tenant().equals(tenant)).map(Completion::topic).toList();
    }

    List<Failure> failures() {
        return List.copyOf(failures);
    }

    List<String> fetchBodies(String tenant) {
        return List.copyOf(fetchBodies.getOrDefault(tenant, new ConcurrentLinkedQueue<>()));
    }

    /** The Authorization header of the most recent poll of this tenant, or null if it was never polled. */
    String lastFetchAuthorization(String tenant) {
        return lastFetchAuthorization.get(tenant);
    }

    long fetchCount(String tenant) {
        AtomicLong count = fetchCounts.get(tenant);
        return count == null ? 0 : count.get();
    }

    long fetchCount() {
        return fetchCounts.values().stream().mapToLong(AtomicLong::get).sum();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            String body = new String(raw, StandardCharsets.UTF_8);
            Matcher path = PATH.matcher(exchange.getRequestURI().getPath());
            if (!path.matches()) {
                reply(exchange, 404, "{}");
                return;
            }
            if (rejectCredentials) {
                reply(exchange, 401, "{\"message\":\"Unauthorized\"}");
                return;
            }
            String tenant = path.group(1);
            String action = path.group(2);
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (action.equals("version")) {
                reply(exchange, 200, "{\"version\":\"3.1.1\"}");
            } else if (action.equals("external-task/fetchAndLock")) {
                fetchCounts.computeIfAbsent(tenant, t -> new AtomicLong()).incrementAndGet();
                fetchBodies.computeIfAbsent(tenant, t -> new ConcurrentLinkedQueue<>()).add(body);
                if (authorization != null) {
                    lastFetchAuthorization.put(tenant, authorization);
                }
                reply(exchange, 200, fetchResponse(tenant, body));
            } else if (path.group(3) != null) {
                Task task = tasksById.get(path.group(3));
                completions.add(new Completion(tenant, task == null ? null : task.topic(), path.group(3),
                        task == null ? null : task.processInstanceId(), authorization, body));
                String next = task == null ? null : NEXT_TOPIC.get(task.topic());
                if (next != null) {
                    // The process moves on. The old demo process passes the name on as a process variable (what the
                    // return-name worker returned); the worker details process carries the variables it was started with.
                    String variables = task.topic().equals("return-name")
                            ? "{\"customerName\":{\"type\":\"String\",\"value\":\"Rajiv Verma\",\"valueInfo\":{}}}"
                            : task.variablesJson();
                    offer(tenant, next, task.processInstanceId(), variables);
                }
                exchange.sendResponseHeaders(204, -1);
            } else {
                Task task = tasksById.get(path.group(4));
                failures.add(new Failure(tenant, task == null ? null : task.topic(), path.group(4), body));
                exchange.sendResponseHeaders(204, -1);
            }
        }
    }

    /** Long polling: hand out a task for a requested topic if there is one, else hold the request briefly. */
    private String fetchResponse(String tenant, String requestBody) {
        Queue<Task> queue = offered.get(tenant);
        if (queue != null) {
            for (Task task : queue) {
                if (requestBody.contains("\"topicName\":\"" + task.topic() + "\"") && queue.remove(task)) {
                    return "[{\"activityId\":\"" + task.topic() + "-activity\",\"activityInstanceId\":\"ai-1\",\"executionId\":\"ex-1\","
                            + "\"id\":\"" + task.id() + "\",\"lockExpirationTime\":\"2030-01-01T00:00:00.000+00:00\","
                            + "\"processDefinitionId\":\"pd-1\",\"processDefinitionKey\":\"isaDemoProcess\","
                            + "\"processInstanceId\":\"" + task.processInstanceId() + "\",\"tenantId\":null,\"retries\":null,"
                            + "\"workerId\":\"w\",\"priority\":0,\"topicName\":\"" + task.topic() + "\",\"businessKey\":null,"
                            + "\"variables\":" + task.variablesJson() + "}]";
                }
            }
        }
        try {
            Thread.sleep(150);                      // a stand-in for the engine holding the long poll
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "[]";
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
