package com.kubedesk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kubedesk.service.ClusterService;
import com.kubedesk.service.Dtos.HealthFinding;
import com.kubedesk.service.Dtos.HealthSeverity;
import com.kubedesk.service.Dtos.ResourceData;
import com.kubedesk.service.ResourceKind;
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
 * and exposes a handful of <em>read-only</em> tools ({@code health_scan}, {@code list_namespaces},
 * {@code list_resource}, {@code describe_yaml}, {@code pod_logs}) that reuse {@link ClusterService}.
 * There is no authentication yet (none is needed while everything is read-only and loopback-bound),
 * and no write tools — those, with their gating/auth, come later.
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

    /**
     * Advisory guidance returned to the client in {@code initialize} (the spec's {@code instructions}
     * field). It tells the model this server is read-only and how a change reaches the cluster: the
     * human applies it from KubeDesk's UI. This keeps the server honestly read-only while still letting
     * an agent be useful about writes — by handing the user ready-to-apply YAML and the exact gesture.
     */
    private static final String INSTRUCTIONS =
            "KubeDesk exposes this Kubernetes cluster READ-ONLY over MCP — there are no write tools, so "
            + "you cannot create, change, scale, restart or delete anything yourself. When the user "
            + "wants a change, never claim you applied it. Instead: produce the complete, ready-to-apply "
            + "YAML manifest (use describe_yaml to fetch the current manifest as a starting point), then "
            + "tell the user to apply it in KubeDesk — click the blue \"+ Create\" button at the "
            + "bottom-left (it opens \"Create / Apply YAML\", does a server-side apply, and offers a "
            + "Dry-run first), or open the resource's YAML tab and click Apply. Edits take effect only "
            + "when the user clicks Apply.";

    /** A single cluster call can hang if the API server is slow/unreachable; bound every tool call. */
    private static final int TOOL_TIMEOUT_SECONDS = 15;

    private static final String DESC_CONTEXT =
            "Kube context to use. Defaults to the context currently selected in KubeDesk.";
    private static final String DESC_DEFAULT_NS =
            "Defaults to the namespace currently selected in KubeDesk.";

    private final ClusterService service;
    private final String serverVersion;
    /** Current context/namespace selected in the UI, used as defaults when the agent omits them. */
    private final Supplier<String> defaultContext;
    private final Supplier<String> defaultNamespace;

    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService workers = Executors.newCachedThreadPool(daemon());
    /** The live-mirror channel: every tool call is recorded here for the UI to reflect. */
    private final McpActivity activity = new McpActivity();

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

    /** The activity channel the UI subscribes to in order to mirror the agent's calls. */
    public McpActivity activity() {
        return activity;
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
        r.put("instructions", INSTRUCTIONS);
        return r;
    }

    private ObjectNode handleToolsList() {
        ObjectNode r = json.createObjectNode();
        ArrayNode tools = r.putArray("tools");

        ObjectNode health = addTool(tools, "health_scan",
                "Scan a namespace (plus cluster Nodes) for resources failing common health checks — "
                + "CrashLoopBackOff, ImagePullBackOff, OOMKilled, Evicted, unschedulable or high-restart "
                + "pods, unavailable workloads, failed Jobs, unbound PVCs, orphaned Services and "
                + "unhealthy/cordoned Nodes. Returns findings ranked most-severe first.");
        ObjectNode healthProps = props(health);
        strProp(healthProps, "context", DESC_CONTEXT);
        strProp(healthProps, "namespace", "Namespace to scan. " + DESC_DEFAULT_NS);
        strProp(healthProps, "severity",
                "Optional: return only findings at this severity (filtered server-side to save tokens).")
                .putArray("enum").add("critical").add("warning");

        ObjectNode listNs = addTool(tools, "list_namespaces",
                "List the namespaces visible in a context. Useful for discovering what to scan.");
        strProp(props(listNs), "context", DESC_CONTEXT);

        ObjectNode listRes = addTool(tools, "list_resource",
                "List a resource kind as a compact table (columns + rows), the same data KubeDesk shows. "
                + "Kinds: " + kindList() + ".");
        ObjectNode listProps = props(listRes);
        strProp(listProps, "kind", "Resource kind to list, e.g. 'Pods' or 'Deployments'.");
        strProp(listProps, "namespace", "Namespace (ignored for cluster-scoped Nodes). " + DESC_DEFAULT_NS);
        strProp(listProps, "context", DESC_CONTEXT);
        strProp(listProps, "nameContains",
                "Optional case-insensitive substring filter applied server-side (saves tokens).");
        intProp(listProps, "limit", "Optional max rows to return (the rest are counted as 'truncated').");

        ObjectNode describe = addTool(tools, "describe_yaml",
                "Fetch a single resource's full manifest as YAML (detail on demand).");
        ObjectNode descProps = props(describe);
        strProp(descProps, "kind", "Resource kind, e.g. 'Pods' or 'Deployments'.");
        strProp(descProps, "name", "Resource name.");
        strProp(descProps, "namespace", "Namespace (ignored for Nodes). " + DESC_DEFAULT_NS);
        strProp(descProps, "context", DESC_CONTEXT);

        ObjectNode logs = addTool(tools, "pod_logs",
                "Fetch the tail of a pod container's logs.");
        ObjectNode logProps = props(logs);
        strProp(logProps, "pod", "Pod name.");
        strProp(logProps, "namespace", "Namespace. " + DESC_DEFAULT_NS);
        strProp(logProps, "container",
                "Optional container name; defaults to the pod's default container.");
        strProp(logProps, "context", DESC_CONTEXT);
        intProp(logProps, "tailLines", "Trailing lines to return (default 200, max 2000).");

        return r;
    }

    private ObjectNode handleToolsCall(JsonNode req) {
        JsonNode params = req.path("params");
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        ObjectNode result = switch (name) {
            case "health_scan" -> toolHealthScan(args);
            case "list_namespaces" -> toolListNamespaces(args);
            case "list_resource" -> toolListResource(args);
            case "describe_yaml" -> toolDescribeYaml(args);
            case "pod_logs" -> toolPodLogs(args);
            default -> toolError("Unknown tool: " + name);
        };
        recordActivity(name, args, result);
        return result;
    }

    /**
     * Mirror a just-finished tool call onto the {@link #activity} channel: a one-line human summary,
     * the resource the UI could reveal (if any), and a compact outcome derived from the result — a
     * count for the list/scan tools, the first error line for failures. Best-effort and swallowing:
     * mirroring must never affect the response the agent receives.
     */
    private void recordActivity(String tool, JsonNode args, ObjectNode result) {
        try {
            boolean isError = result.path("isError").asBoolean(false);
            String text = result.path("content").path(0).path("text").asText("");

            String ns = text(args, "namespace", defaultNamespace.get());
            ResourceKind kind = parseKind(text(args, "kind", null));
            String name = text(args, "name", null);
            String pod = text(args, "pod", null);

            String summary;
            ResourceKind navKind = null;
            String navName = null;
            String navNamespace = null;
            switch (tool) {
                case "health_scan" -> summary = "health scan · " + nsLabel(ns);
                case "list_namespaces" -> summary = "list namespaces";
                case "list_resource" -> {
                    summary = (kind == null ? "list" : kind.label())
                            + (kind != null && kind.namespaced() ? " · " + nsLabel(ns) : "");
                    navKind = kind;
                    navNamespace = ns;
                }
                case "describe_yaml" -> {
                    summary = "describe " + (kind == null ? "" : kind.label() + "/") + safe(name);
                    navKind = kind;
                    navName = name;
                    navNamespace = ns;
                }
                case "pod_logs" -> {
                    summary = "logs " + safe(pod);
                    navKind = ResourceKind.PODS;
                    navName = pod;
                    navNamespace = ns;
                }
                default -> summary = tool;
            }

            McpActivity.Status status = isError ? McpActivity.Status.ERROR : McpActivity.Status.OK;
            String outcome = isError ? firstLine(text) : okOutcome(tool, text);
            activity.record(tool, summary, status, outcome, navKind, navName, navNamespace);
        } catch (RuntimeException ignored) {
            // Never let mirroring bookkeeping disturb the tool response.
        }
    }

    /** A compact success outcome: pull a count from the JSON tools, else a size for text tools. */
    private String okOutcome(String tool, String text) {
        try {
            JsonNode n = json.readTree(text);
            if (n.path("summary").has("total")) {
                return n.path("summary").path("total").asInt() + " findings";
            }
            if (n.path("namespaces").isArray()) {
                return n.path("namespaces").size() + " namespaces";
            }
            if (n.has("total")) {
                int total = n.path("total").asInt();
                int more = n.path("truncated").asInt(0);
                return total + (total == 1 ? " row" : " rows") + (more > 0 ? " (" + more + " more)" : "");
            }
        } catch (Exception notJson) {
            // describe_yaml / pod_logs return YAML or plain text — fall through to a size.
        }
        if ("pod_logs".equals(tool)) {
            long lines = text.isEmpty() ? 0 : text.lines().count();
            return lines + (lines == 1 ? " line" : " lines");
        }
        if ("describe_yaml".equals(tool)) {
            return "yaml";
        }
        return "ok";
    }

    private static String nsLabel(String ns) {
        return (ns == null || ns.isBlank()) ? "—" : ns;
    }

    private static String firstLine(String s) {
        if (s == null || s.isBlank()) {
            return "failed";
        }
        String first = s.lines().findFirst().orElse(s);
        return first.length() > 140 ? first.substring(0, 139) + "…" : first;
    }

    // --- tools ------------------------------------------------------------------------------

    private ObjectNode toolHealthScan(JsonNode args) {
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
            return toolError(timeoutMsg());
        } catch (Exception e) {
            return toolError(describeFailure(e, context, namespace));
        }
    }

    private ObjectNode toolListNamespaces(JsonNode args) {
        String context = text(args, "context", defaultContext.get());
        try {
            final String ctx = context;
            List<String> namespaces = runWithTimeout(() -> service.listNamespaces(ctx));
            ObjectNode root = json.createObjectNode();
            root.put("context", context);
            ArrayNode arr = root.putArray("namespaces");
            for (String n : namespaces) {
                arr.add(n);
            }
            return toolText(json.writeValueAsString(root), false);
        } catch (TimeoutException te) {
            return toolError(timeoutMsg());
        } catch (Exception e) {
            return toolError(describeFailure(e, context, null));
        }
    }

    private ObjectNode toolListResource(JsonNode args) {
        String context = text(args, "context", defaultContext.get());
        ResourceKind kind = parseKind(text(args, "kind", null));
        if (kind == null) {
            return toolError("Missing or unknown 'kind'. One of: " + kindList());
        }
        String namespace = text(args, "namespace", defaultNamespace.get());
        if (kind.namespaced() && (namespace == null || namespace.isBlank())) {
            return toolError("'" + kind.label() + "' is namespaced — pass a 'namespace' argument.");
        }
        String nameContains = text(args, "nameContains", null);
        Integer limit = intOrNull(args, "limit");
        try {
            final String ctx = context;
            final String ns = namespace;
            final ResourceKind k = kind;
            ResourceData data = runWithTimeout(() -> service.listResource(ctx, ns, k));
            return toolText(renderResource(data, nameContains, limit), false);
        } catch (TimeoutException te) {
            return toolError(timeoutMsg());
        } catch (Exception e) {
            return toolError(describeFailure(e, context, namespace));
        }
    }

    private ObjectNode toolDescribeYaml(JsonNode args) {
        String context = text(args, "context", defaultContext.get());
        ResourceKind kind = parseKind(text(args, "kind", null));
        if (kind == null) {
            return toolError("Missing or unknown 'kind'. One of: " + kindList());
        }
        String name = text(args, "name", null);
        if (name == null) {
            return toolError("Missing 'name'.");
        }
        String namespace = text(args, "namespace", defaultNamespace.get());
        if (kind.namespaced() && (namespace == null || namespace.isBlank())) {
            return toolError("'" + kind.label() + "' is namespaced — pass a 'namespace' argument.");
        }
        try {
            final String ctx = context;
            final String ns = namespace;
            final String nm = name;
            final ResourceKind k = kind;
            String yaml = runWithTimeout(() -> service.describeYaml(ctx, ns, k, nm));
            return toolText(yaml == null ? "" : yaml, false);
        } catch (TimeoutException te) {
            return toolError(timeoutMsg());
        } catch (Exception e) {
            return toolError(describeFailure(e, context, namespace));
        }
    }

    private ObjectNode toolPodLogs(JsonNode args) {
        String context = text(args, "context", defaultContext.get());
        String namespace = text(args, "namespace", defaultNamespace.get());
        String pod = text(args, "pod", null);
        String container = text(args, "container", null);
        Integer tail = intOrNull(args, "tailLines");
        int tailLines = tail == null ? 200 : Math.max(1, Math.min(tail, 2000));
        if (pod == null) {
            return toolError("Missing 'pod'.");
        }
        if (namespace == null || namespace.isBlank()) {
            return toolError("Missing 'namespace'.");
        }
        try {
            final String ctx = context;
            final String ns = namespace;
            final String p = pod;
            final String c = container;
            final int tl = tailLines;
            String out = runWithTimeout(() -> service.podLogs(ctx, ns, p, c, tl));
            return toolText(out == null ? "" : out, false);
        } catch (TimeoutException te) {
            return toolError(timeoutMsg());
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

    /**
     * Render table data as compact JSON {@code {columns, rows, total[, truncated]}}. Columns+rows (not
     * an array of objects) keeps field names from repeating on every row — cheaper in tokens. The
     * optional {@code nameContains} filter and {@code limit} are applied here, server-side.
     */
    private String renderResource(ResourceData data, String nameContains, Integer limit)
            throws IOException {
        String needle = (nameContains == null || nameContains.isBlank())
                ? null : nameContains.toLowerCase();
        int matched = 0;
        int included = 0;
        ArrayNode rows = json.createArrayNode();
        for (List<String> row : data.rows()) {
            if (needle != null && !rowContains(row, needle)) {
                continue;
            }
            matched++;
            if (limit != null && included >= limit) {
                continue;
            }
            ArrayNode r = rows.addArray();
            for (String cell : row) {
                r.add(cell);
            }
            included++;
        }
        ObjectNode root = json.createObjectNode();
        ArrayNode cols = root.putArray("columns");
        for (String c : data.columns()) {
            cols.add(c);
        }
        root.set("rows", rows);
        root.put("total", matched);
        if (matched > included) {
            root.put("truncated", matched - included);
        }
        return json.writeValueAsString(root);
    }

    private static boolean rowContains(List<String> row, String needleLower) {
        for (String cell : row) {
            if (cell != null && cell.toLowerCase().contains(needleLower)) {
                return true;
            }
        }
        return false;
    }

    private static ResourceKind parseKind(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        for (ResourceKind k : ResourceKind.values()) {
            if (k.label().equalsIgnoreCase(t) || k.name().equalsIgnoreCase(t)
                    || k.resource().equalsIgnoreCase(t)) {
                return k;
            }
        }
        return null;
    }

    private static String kindList() {
        StringBuilder sb = new StringBuilder();
        for (ResourceKind k : ResourceKind.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(k.label());
        }
        return sb.toString();
    }

    // --- tool-schema builders ---------------------------------------------------------------

    /** Add a read-only tool with an empty object inputSchema; fill its properties via {@link #props}. */
    private ObjectNode addTool(ArrayNode tools, String name, String description) {
        ObjectNode t = tools.addObject();
        t.put("name", name);
        t.put("description", description);
        ObjectNode schema = t.putObject("inputSchema");
        schema.put("type", "object");
        schema.putObject("properties");
        t.putObject("annotations").put("readOnlyHint", true);
        return t;
    }

    private static ObjectNode props(ObjectNode tool) {
        return (ObjectNode) tool.get("inputSchema").get("properties");
    }

    private static ObjectNode strProp(ObjectNode props, String name, String description) {
        ObjectNode p = props.putObject(name);
        p.put("type", "string");
        p.put("description", description);
        return p;
    }

    private static void intProp(ObjectNode props, String name, String description) {
        ObjectNode p = props.putObject(name);
        p.put("type", "integer");
        p.put("description", description);
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

    private static String timeoutMsg() {
        return "The call timed out after " + TOOL_TIMEOUT_SECONDS
                + "s (the API server is slow or unreachable).";
    }

    private static Integer intOrNull(JsonNode args, String field) {
        if (args == null) {
            return null;
        }
        JsonNode v = args.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.asInt();
        }
        try {
            return Integer.parseInt(v.asText().trim());
        } catch (NumberFormatException e) {
            return null;
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
