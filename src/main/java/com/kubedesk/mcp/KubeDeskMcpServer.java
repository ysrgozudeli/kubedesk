package com.kubedesk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kubedesk.service.ClusterService;
import com.kubedesk.service.Dtos.HealthFinding;
import com.kubedesk.service.Dtos.HealthSeverity;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.client.KubernetesClientException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * A tiny, embedded <b>Model Context Protocol</b> server — the v1 "give an AI agent a read-only view
 * of the cluster" slice.
 *
 * <p><b>Experimental, dev/testing use only.</b> It binds <em>loopback only</em> ({@code 127.0.0.1})
 * and currently exposes a single <em>read-only</em> tool, {@code health_scan}, that reuses
 * {@link ClusterService#healthCheck}. There is no authentication yet (none is needed while everything
 * is read-only and loopback-bound), and no write tools — those, with their gating/auth, come later.
 *
 * <p>Transport is plain <b>Streamable HTTP</b> (the 2025 MCP transport) without SSE: every request is a
 * single JSON-RPC POST answered with a single JSON body, which is all a request/response tool needs.
 * The protocol surface is deliberately minimal — {@code initialize}, {@code tools/list},
 * {@code tools/call}, {@code ping} — built on the JDK's own {@link HttpServer} and the Jackson that
 * fabric8 already puts on the classpath, so it adds no dependencies. Tool logic is kept separate from
 * the transport so a full MCP SDK (sessions, SSE, OAuth) can be swapped in later without touching it.
 */
public final class KubeDeskMcpServer {

    /**
     * The MCP protocol revision we implement. We echo back the client's requested version when it sends
     * one (these base methods are stable across the 2024-11-05 → 2025-06-18 revisions, so negotiation
     * succeeds), falling back to this when it doesn't.
     */
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String SERVER_NAME = "kubedesk";

    /** A single cluster call can hang if the API server is slow/unreachable; bound every tool call. */
    private static final int TOOL_TIMEOUT_SECONDS = 15;

    private final ClusterService service;
    private final String serverVersion;
    /** Current context/namespace selected in the UI, used as defaults when the agent omits them. */
    private final Supplier<String> defaultContext;
    private final Supplier<String> defaultNamespace;

    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService workers = Executors.newCachedThreadPool(daemon());

    private volatile HttpServer http;
    private volatile int boundPort = -1;

    public KubeDeskMcpServer(ClusterService service, String serverVersion,
                             Supplier<String> defaultContext, Supplier<String> defaultNamespace) {
        this.service = service;
        this.serverVersion = serverVersion == null || serverVersion.isBlank() ? "dev" : serverVersion;
        this.defaultContext = defaultContext;
        this.defaultNamespace = defaultNamespace;
    }

    // --- lifecycle --------------------------------------------------------------------------

    /** Start listening on {@code 127.0.0.1:port}. No-op if already running. */
    public synchronized void start(int port) throws IOException {
        if (http != null) {
            return;
        }
        HttpServer s = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        s.createContext("/mcp", this::handle);
        s.setExecutor(workers);
        s.start();
        http = s;
        boundPort = s.getAddress().getPort();
    }

    /** Stop listening. No-op if not running. */
    public synchronized void stop() {
        HttpServer s = http;
        if (s != null) {
            s.stop(0);
            http = null;
            boundPort = -1;
        }
    }

    public boolean isRunning() {
        return http != null;
    }

    public int port() {
        return boundPort;
    }

    public String endpoint() {
        return "http://127.0.0.1:" + boundPort + "/mcp";
    }

    // --- request handling -------------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, errorEnvelope(null, -32600, "Only HTTP POST is supported."));
                return;
            }

            JsonNode req;
            try (InputStream in = ex.getRequestBody()) {
                req = json.readTree(in.readAllBytes());
            } catch (Exception parse) {
                sendJson(ex, 200, errorEnvelope(null, -32700, "Parse error: invalid JSON."));
                return;
            }
            if (req == null || !req.isObject()) {
                // JSON-RPC batches (arrays) aren't supported in this minimal v1.
                sendJson(ex, 200, errorEnvelope(null, -32600, "Invalid request (expected a JSON object)."));
                return;
            }

            JsonNode id = req.get("id");
            boolean isNotification = id == null || id.isNull();
            String method = req.path("method").asText("");

            ObjectNode result;
            switch (method) {
                case "initialize" -> result = handleInitialize(req);
                case "tools/list" -> result = handleToolsList();
                case "tools/call" -> result = handleToolsCall(req);
                case "ping" -> result = json.createObjectNode();
                case "notifications/initialized", "notifications/cancelled" -> {
                    // Fire-and-forget notifications: acknowledge with no body.
                    ex.sendResponseHeaders(202, -1);
                    ex.close();
                    return;
                }
                default -> {
                    if (isNotification) {
                        ex.sendResponseHeaders(202, -1);
                        ex.close();
                    } else {
                        sendJson(ex, 200, errorEnvelope(id, -32601, "Method not found: " + method));
                    }
                    return;
                }
            }

            if (isNotification) {
                ex.sendResponseHeaders(202, -1);
                ex.close();
            } else {
                sendJson(ex, 200, resultEnvelope(id, result));
            }
        } catch (Exception e) {
            // Last-ditch: never leak a stack trace to the wire.
            try {
                sendJson(ex, 200, errorEnvelope(null, -32603, "Internal error: " + safe(e.getMessage())));
            } catch (IOException ignored) {
                // client gone
            }
        }
    }

    private ObjectNode handleInitialize(JsonNode req) {
        String clientVersion = req.path("params").path("protocolVersion").asText("");
        ObjectNode r = json.createObjectNode();
        r.put("protocolVersion", clientVersion.isBlank() ? PROTOCOL_VERSION : clientVersion);
        // We offer tools (no listChanged notifications in v1).
        r.putObject("capabilities").putObject("tools");
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", SERVER_NAME);
        info.put("version", serverVersion);
        return r;
    }

    private ObjectNode handleToolsList() {
        ObjectNode r = json.createObjectNode();
        ObjectNode tool = r.putArray("tools").addObject();
        tool.put("name", "health_scan");
        tool.put("description",
                "Scan a namespace (plus cluster Nodes) for resources failing common health checks — "
                + "CrashLoopBackOff, ImagePullBackOff, OOMKilled, Evicted, unschedulable or high-restart "
                + "pods, unavailable workloads, failed Jobs, unbound PVCs, orphaned Services and "
                + "unhealthy/cordoned Nodes. Returns findings ranked most-severe first. Read-only.");

        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode ns = props.putObject("namespace");
        ns.put("type", "string");
        ns.put("description",
                "Namespace to scan. Defaults to the namespace currently selected in KubeDesk.");
        ObjectNode ctx = props.putObject("context");
        ctx.put("type", "string");
        ctx.put("description",
                "Kube context to use. Defaults to the context currently selected in KubeDesk.");
        ObjectNode sev = props.putObject("severity");
        sev.put("type", "string");
        ArrayNode en = sev.putArray("enum");
        en.add("critical");
        en.add("warning");
        sev.put("description",
                "Optional: return only findings at this severity (filtered server-side to save tokens).");

        ObjectNode ann = tool.putObject("annotations");
        ann.put("title", "Cluster health scan");
        ann.put("readOnlyHint", true);
        return r;
    }

    private ObjectNode handleToolsCall(JsonNode req) {
        JsonNode params = req.path("params");
        String name = params.path("name").asText("");
        if (!"health_scan".equals(name)) {
            return toolError("Unknown tool: " + name);
        }
        JsonNode args = params.path("arguments");
        String context = text(args, "context", defaultContext.get());
        String namespace = text(args, "namespace", defaultNamespace.get());
        String severity = text(args, "severity", null);

        if (namespace == null || namespace.isBlank()) {
            return toolError("No namespace was given and none is selected in KubeDesk — "
                    + "pass a 'namespace' argument.");
        }
        try {
            final String ctx = context;
            final String ns = namespace;
            List<HealthFinding> findings = runWithTimeout(() -> service.healthCheck(ctx, ns));
            return toolText(renderFindings(context, namespace, severity, findings), false);
        } catch (TimeoutException te) {
            return toolError("Cluster health scan timed out after " + TOOL_TIMEOUT_SECONDS
                    + "s (the API server is slow or unreachable).");
        } catch (Exception e) {
            return toolError(describeFailure(e, context, namespace));
        }
    }

    /**
     * Render findings as compact JSON text (no pretty-printing — the agent pays for every byte). The
     * {@code summary} counts the whole scan; {@code findings[]} honours the optional severity filter.
     */
    private String renderFindings(String context, String namespace, String severityFilter,
                                  List<HealthFinding> findings) throws IOException {
        int crit = 0;
        int warn = 0;
        ArrayNode arr = json.createArrayNode();
        for (HealthFinding f : findings) {
            boolean isCritical = f.severity() == HealthSeverity.CRITICAL;
            if (isCritical) {
                crit++;
            } else {
                warn++;
            }
            if (severityFilter != null) {
                if ("critical".equalsIgnoreCase(severityFilter) && !isCritical) {
                    continue;
                }
                if ("warning".equalsIgnoreCase(severityFilter) && isCritical) {
                    continue;
                }
            }
            ObjectNode o = arr.addObject();
            o.put("severity", f.severity().name());
            o.put("kind", f.kind().label());
            o.put("name", f.name());
            o.put("check", f.check());
            o.put("detail", f.detail());
        }
        ObjectNode root = json.createObjectNode();
        root.put("context", context);
        root.put("namespace", namespace);
        ObjectNode summary = root.putObject("summary");
        summary.put("critical", crit);
        summary.put("warning", warn);
        summary.put("total", crit + warn);
        root.set("findings", arr);
        return json.writeValueAsString(root);
    }

    // --- failure mapping --------------------------------------------------------------------

    /** Turn a cluster failure into one concise, actionable line (never a stack trace). */
    private static String describeFailure(Throwable e, String context, String namespace) {
        int code = httpStatusOf(e);
        return switch (code) {
            case 401 -> "Authentication failed (401) — the kubeconfig credentials for context '"
                    + context + "' may be expired.";
            case 403 -> "Forbidden (403) — this kubeconfig user can't scan namespace '" + namespace
                    + "' in context '" + context + "'.";
            case 404 -> "Not found (404) — context '" + context + "' or namespace '" + namespace
                    + "' does not exist.";
            default -> "Health scan failed: " + rootMessage(e);
        };
    }

    private static int httpStatusOf(Throwable e) {
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof KubernetesClientException kce) {
                return kce.getCode();
            }
        }
        return -1;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        if (m == null || m.isBlank()) {
            return t.getClass().getSimpleName();
        }
        return m.lines().findFirst().orElse(m); // first line only — keep it terse
    }

    // --- helpers ----------------------------------------------------------------------------

    private <T> T runWithTimeout(Callable<T> task) throws Exception {
        Future<T> f = workers.submit(task);
        try {
            return f.get(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            f.cancel(true);
            throw te;
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof Exception ce) {
                throw ce;
            }
            throw ee;
        }
    }

    private ObjectNode resultEnvelope(JsonNode id, ObjectNode result) {
        ObjectNode env = json.createObjectNode();
        env.put("jsonrpc", "2.0");
        env.set("id", id);
        env.set("result", result);
        return env;
    }

    private ObjectNode errorEnvelope(JsonNode id, int code, String message) {
        ObjectNode env = json.createObjectNode();
        env.put("jsonrpc", "2.0");
        if (id == null || id.isNull()) {
            env.putNull("id");
        } else {
            env.set("id", id);
        }
        ObjectNode err = env.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return env;
    }

    /** A {@code tools/call} result carrying text. Tool-level failures use {@code isError:true} (not a
     *  JSON-RPC error) so the agent sees and can react to them. */
    private ObjectNode toolText(String textValue, boolean isError) {
        ObjectNode r = json.createObjectNode();
        ObjectNode block = r.putArray("content").addObject();
        block.put("type", "text");
        block.put("text", textValue);
        r.put("isError", isError);
        return r;
    }

    private ObjectNode toolError(String message) {
        return toolText(message, true);
    }

    private void sendJson(HttpExchange ex, int status, ObjectNode body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null) {
            return fallback;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return fallback;
        }
        String s = v.asText();
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static ThreadFactory daemon() {
        return r -> {
            Thread t = new Thread(r, "kubedesk-mcp");
            t.setDaemon(true);
            return t;
        };
    }
}
