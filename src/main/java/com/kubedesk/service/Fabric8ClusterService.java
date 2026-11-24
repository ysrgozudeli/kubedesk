package com.kubedesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import com.kubedesk.service.Dtos.ContextInfo;
import com.kubedesk.service.Dtos.HealthFinding;
import com.kubedesk.service.Dtos.HelmRelease;
import com.kubedesk.service.Dtos.MetricSample;
import com.kubedesk.service.Dtos.Permissions;
import com.kubedesk.service.Dtos.ResourceRuleDto;
import com.kubedesk.service.Dtos.HealthSeverity;
import com.kubedesk.service.Dtos.HealthSummary;
import com.kubedesk.service.Dtos.ResourceData;
import com.kubedesk.service.Dtos.TopoEdge;
import com.kubedesk.service.Dtos.TopoNode;
import com.kubedesk.service.Dtos.TopologyData;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamedContext;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeCondition;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectRulesReview;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectRulesReviewBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.dsl.BytesLimitTerminateTimeTailPrettyLoggable;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.dsl.TimeTailPrettyLoggable;
import io.fabric8.kubernetes.client.dsl.TimestampBytesLimitTerminateTimeTailPrettyLoggable;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.internal.KubeConfigUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * {@link ClusterService} implementation backed by the fabric8 Kubernetes client.
 *
 * <p>It reads the user's kube.conf and makes REST/watch calls straight to the cluster API server —
 * no {@code kubectl} binary is required for any of the operations here.
 */
public class Fabric8ClusterService implements ClusterService {

    /**
     * When set, this exact kube.conf file is used for everything (contexts + clients).
     * When {@code null}, we fall back to kubectl-style discovery ($KUBECONFIG, then ~/.kube/config).
     * Lets the user point KubeDesk at any config file — handy when the default one is expired.
     */
    private volatile File kubeConfigOverride;

    @Override
    public void setKubeConfigFile(File file) {
        this.kubeConfigOverride = file;
    }

    @Override
    public File activeKubeConfigFile() {
        return kubeConfigFile();
    }

    /** Locate the active kube.conf: explicit override, else $KUBECONFIG, then ~/.kube/config. */
    private File kubeConfigFile() {
        if (kubeConfigOverride != null) {
            return kubeConfigOverride;
        }
        String env = System.getenv("KUBECONFIG");
        if (env != null && !env.isBlank()) {
            // KUBECONFIG may list several files; the first wins for our MVP.
            String first = env.split(File.pathSeparator)[0].trim();
            if (!first.isEmpty()) {
                return new File(first);
            }
        }
        return new File(System.getProperty("user.home"), ".kube" + File.separator + "config");
    }

    @Override
    public List<ContextInfo> listContexts() {
        return listContexts(kubeConfigFile());
    }

    @Override
    public List<ContextInfo> listContexts(File file) {
        try {
            if (file == null || !file.exists()) {
                return List.of();
            }
            io.fabric8.kubernetes.api.model.Config kc = KubeConfigUtils.parseConfig(file);
            String current = kc.getCurrentContext();
            List<ContextInfo> out = new ArrayList<>();
            List<NamedContext> contexts = kc.getContexts();
            if (contexts != null) {
                for (NamedContext c : contexts) {
                    var ctx = c.getContext();
                    out.add(new ContextInfo(
                            c.getName(),
                            ctx != null ? ctx.getCluster() : "",
                            ctx != null ? ctx.getUser() : "",
                            ctx != null && ctx.getNamespace() != null ? ctx.getNamespace() : "default",
                            c.getName().equals(current)));
                }
            }
            return out;
        } catch (Exception e) {
            throw new ClusterException("Could not read kube.conf: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> listNamespaces(String context) {
        try (KubernetesClient client = clientFor(context)) {
            return client.namespaces().list().getItems().stream()
                    .map(Namespace::getMetadata)
                    .map(m -> m.getName())
                    .sorted()
                    .collect(Collectors.toList());
        } catch (KubernetesClientException e) {
            // A namespace-scoped token (common on AKS and other managed clusters) is often forbidden
            // from listing namespaces cluster-wide. Don't fail the whole load — return an empty list
            // and let the UI fall back to the namespace declared in the kubeconfig context.
            if (e.getCode() == 403 || e.getCode() == 401) {
                return List.of();
            }
            throw new ClusterException("Could not list namespaces: " + rootMessage(e), e);
        } catch (Exception e) {
            throw new ClusterException("Could not list namespaces: " + rootMessage(e), e);
        }
    }

    @Override
    public Permissions permissions(String context, String namespace) {
        try (KubernetesClient client = clientFor(context)) {
            SelfSubjectRulesReview review = new SelfSubjectRulesReviewBuilder()
                    .withNewSpec().withNamespace(namespace).endSpec().build();
            SelfSubjectRulesReview result = client.authorization().v1()
                    .selfSubjectRulesReview().create(review);
            var status = result != null ? result.getStatus() : null;
            if (status == null) {
                return Permissions.unknown();
            }
            boolean complete = !Boolean.TRUE.equals(status.getIncomplete());
            List<ResourceRuleDto> rules = new ArrayList<>();
            if (status.getResourceRules() != null) {
                for (var r : status.getResourceRules()) {
                    rules.add(new ResourceRuleDto(setOf(r.getVerbs()), setOf(r.getApiGroups()),
                            setOf(r.getResources())));
                }
            }
            return new Permissions(rules, complete, true);
        } catch (Exception e) {
            return Permissions.unknown(); // fail open — never hide access we can't verify
        }
    }

    private java.util.Set<String> setOf(List<String> list) {
        return list == null ? java.util.Set.of() : new java.util.HashSet<>(list);
    }

    @Override
    public ResourceData listResource(String context, String namespace, ResourceKind kind) {
        try (KubernetesClient client = clientFor(context)) {
            return dataForKind(client, namespace, kind);
        } catch (Exception e) {
            throw new ClusterException("Could not list " + kind.label() + ": " + rootMessage(e), e);
        }
    }

    private ResourceData dataForKind(KubernetesClient client, String namespace, ResourceKind kind) {
        return switch (kind) {
            case PODS -> pods(client, namespace);
            case DEPLOYMENTS -> deployments(client, namespace);
            case REPLICASETS -> replicaSets(client, namespace);
            case STATEFULSETS -> statefulSets(client, namespace);
            case DAEMONSETS -> daemonSets(client, namespace);
            case JOBS -> jobs(client, namespace);
            case CRONJOBS -> cronJobs(client, namespace);
            case SERVICES -> services(client, namespace);
            case INGRESSES -> ingresses(client, namespace);
            case GATEWAYCLASSES, GATEWAYS, HTTPROUTES, GRPCROUTES, SEALEDSECRETS ->
                    com.kubedesk.service.crd.CrdSupport.list(client, kind.crd(), namespace);
            case CONFIGMAPS -> configMaps(client, namespace);
            case SECRETS -> secrets(client, namespace);
            case PVCS -> pvcs(client, namespace);
            case NODES -> nodes(client);
        };
    }

    @Override
    public ResourceData listResourceAcrossNamespaces(String context, List<String> namespaces,
                                                     ResourceKind kind) {
        try (KubernetesClient client = clientFor(context)) {
            List<String> baseColumns = null;
            List<List<String>> allRows = new ArrayList<>();
            for (String ns : namespaces) {
                if (ns == null || ns.isBlank()) {
                    continue;
                }
                ResourceData d = dataForKind(client, ns, kind);
                if (baseColumns == null) {
                    baseColumns = d.columns();
                }
                for (List<String> row : d.rows()) {
                    List<String> merged = new ArrayList<>(row.size() + 1);
                    merged.add(ns);            // prepend the owning namespace
                    merged.addAll(row);
                    allRows.add(merged);
                }
            }
            List<String> columns = new ArrayList<>();
            columns.add("Namespace");
            if (baseColumns != null) {
                columns.addAll(baseColumns);
            }
            return new ResourceData(columns, allRows);
        } catch (Exception e) {
            throw new ClusterException("Could not list " + kind.label() + " across namespaces: "
                    + rootMessage(e), e);
        }
    }

    @Override
    public boolean apiGroupAvailable(String context, String group) {
        try (KubernetesClient client = clientFor(context)) {
            return com.kubedesk.service.crd.CrdSupport.groupAvailable(client, group);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Dtos.CrdDetails crdDetails(String context, String namespace, ResourceKind kind, String name) {
        var crd = kind.crd();
        if (crd == null || crd.inspector() == null) {
            return new Dtos.CrdDetails(List.of(), List.of());
        }
        try (KubernetesClient client = clientFor(context)) {
            GenericKubernetesResource obj = com.kubedesk.service.crd.CrdSupport.get(client, crd, namespace, name);
            if (obj == null) {
                return new Dtos.CrdDetails(List.of(), List.of());
            }
            return crd.inspector().inspect(client, obj);
        } catch (Exception e) {
            throw new ClusterException("Could not load status for " + name + ": " + rootMessage(e), e);
        }
    }

    @Override
    public HealthSummary podHealth(String context, String namespace) {
        try (KubernetesClient client = clientFor(context)) {
            List<Pod> pods = client.pods().inNamespace(namespace).list().getItems();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Pod p : pods) {
                String phase = p.getStatus() != null && p.getStatus().getPhase() != null
                        ? p.getStatus().getPhase() : "Unknown";
                // "Running" with a container that isn't ready (failing readiness probe) isn't healthy.
                if ("Running".equals(phase) && p.getStatus().getContainerStatuses() != null
                        && p.getStatus().getContainerStatuses().stream()
                        .anyMatch(cs -> !Boolean.TRUE.equals(cs.getReady()))) {
                    phase = "Not ready";
                }
                counts.merge(phase, 1, Integer::sum);
            }
            return new HealthSummary(counts, pods.size());
        } catch (Exception e) {
            throw new ClusterException("Could not summarise pod health: " + rootMessage(e), e);
        }
    }

    @Override
    public String describeYaml(String context, String namespace, ResourceKind kind, String name) {
        return describeYaml(context, namespace, kind, name, false);
    }

    @Override
    public String describeYaml(String context, String namespace, ResourceKind kind, String name,
                               boolean redactSecrets) {
        try (KubernetesClient client = clientFor(context)) {
            Object obj = switch (kind) {
                case PODS -> client.pods().inNamespace(namespace).withName(name).get();
                case DEPLOYMENTS -> client.apps().deployments().inNamespace(namespace).withName(name).get();
                case REPLICASETS -> client.apps().replicaSets().inNamespace(namespace).withName(name).get();
                case STATEFULSETS -> client.apps().statefulSets().inNamespace(namespace).withName(name).get();
                case DAEMONSETS -> client.apps().daemonSets().inNamespace(namespace).withName(name).get();
                case JOBS -> client.batch().v1().jobs().inNamespace(namespace).withName(name).get();
                case CRONJOBS -> client.batch().v1().cronjobs().inNamespace(namespace).withName(name).get();
                case SERVICES -> client.services().inNamespace(namespace).withName(name).get();
                case INGRESSES -> client.network().v1().ingresses().inNamespace(namespace).withName(name).get();
                case GATEWAYCLASSES, GATEWAYS, HTTPROUTES, GRPCROUTES, SEALEDSECRETS ->
                        com.kubedesk.service.crd.CrdSupport.get(client, kind.crd(), namespace, name);
                case CONFIGMAPS -> client.configMaps().inNamespace(namespace).withName(name).get();
                case SECRETS -> client.secrets().inNamespace(namespace).withName(name).get();
                case PVCS -> client.persistentVolumeClaims().inNamespace(namespace).withName(name).get();
                case NODES -> client.nodes().withName(name).get();
            };
            if (obj == null) {
                return "# " + kind.label() + " '" + name + "' not found.";
            }
            // Keep secret material out of an agent's view: redact Secret values (keys/structure stay).
            if (redactSecrets && obj instanceof Secret sec) {
                if (sec.getData() != null) {
                    sec.getData().replaceAll((k, v) -> "<redacted>");
                }
                if (sec.getStringData() != null) {
                    sec.getStringData().replaceAll((k, v) -> "<redacted>");
                }
            }
            return io.fabric8.kubernetes.client.utils.Serialization.asYaml(obj);
        } catch (Exception e) {
            throw new ClusterException("Could not load YAML for " + name + ": " + rootMessage(e), e);
        }
    }

    @Override
    public List<Integer> listPodPorts(String context, String namespace, String pod) {
        try (KubernetesClient client = clientFor(context)) {
            Pod p = client.pods().inNamespace(namespace).withName(pod).get();
            if (p == null || p.getSpec() == null || p.getSpec().getContainers() == null) {
                return List.of();
            }
            return p.getSpec().getContainers().stream()
                    .filter(c -> c.getPorts() != null)
                    .flatMap(c -> c.getPorts().stream())
                    .map(port -> port.getContainerPort())
                    .filter(port -> port != null)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new ClusterException("Could not read ports for " + pod + ": " + rootMessage(e), e);
        }
    }

    @Override
    public PortForward startPortForward(String context, String namespace, String pod,
                                        int remotePort, int localPort) {
        // Long-lived client keeps the tunnel open; both close together via the handle.
        KubernetesClient client = clientFor(context);
        try {
            LocalPortForward lpf = localPort > 0
                    ? client.pods().inNamespace(namespace).withName(pod).portForward(remotePort, localPort)
                    : client.pods().inNamespace(namespace).withName(pod).portForward(remotePort);
            if (!lpf.isAlive()) {
                try {
                    lpf.close();
                } catch (Exception ignore) {
                    // ignore
                }
                throw new ClusterException("Tunnel did not start (port " + localPort
                        + " may be in use).", null);
            }
            return new PortForward() {
                @Override public String context() { return context; }
                @Override public String namespace() { return namespace; }
                @Override public String pod() { return pod; }
                @Override public int remotePort() { return remotePort; }
                @Override public int localPort() { return lpf.getLocalPort(); }
                @Override public boolean isAlive() { return lpf.isAlive(); }
                @Override public void close() {
                    try {
                        lpf.close();
                    } catch (Exception ignore) {
                        // best-effort
                    } finally {
                        client.close();
                    }
                }
            };
        } catch (ClusterException ce) {
            client.close();
            throw ce;
        } catch (Exception e) {
            client.close();
            throw new ClusterException("Could not start port-forward for " + pod + ": "
                    + rootMessage(e), e);
        }
    }

    @Override
    public ExecSession exec(String context, String namespace, String pod, String container,
                            Consumer<String> onOutput, Runnable onClosed) {
        // Long-lived client backs the session; closed together with the ExecWatch.
        KubernetesClient client = clientFor(context);
        try {
            for (String shell : EXEC_SHELLS) {
                ExecAttempt attempt = tryShell(client, namespace, pod, container, shell, onOutput, onClosed);
                if (attempt.session() != null) {
                    return attempt.session();
                }
                if (!attempt.shellMissing()) {
                    throw new ClusterException(explainExecFailure(namespace, attempt.reason()));
                }
                // That shell doesn't exist in the image — try the next one.
            }
            throw new ClusterException("This container has no shell (tried "
                    + String.join(", ", EXEC_SHELLS) + "). It is probably a distroless or scratch "
                    + "image. Pick another container if the pod has one, or debug it from a terminal with: "
                    + "kubectl debug -it " + pod + " -n " + namespace + " --image=busybox"
                    + (container != null ? " --target=" + container : ""));
        } catch (ClusterException ce) {
            client.close();
            throw ce;
        } catch (Exception e) {
            client.close();
            throw new ClusterException(explainExecFailure(namespace, rootMessage(e)), e);
        }
    }

    /**
     * Open a real PTY into the container. Same shell fallback as {@link #exec}, but with a TTY: a live
     * prompt appears as soon as the shell starts, keystrokes go in via {@code sendRaw}, and the merged
     * stdout/stderr byte stream is decoded and fed to {@code onOutput}. There is no marker handshake —
     * a PTY's prompt is the connected signal, and the exit/failure listener still reports a dead shell.
     */
    @Override
    public ExecSession execTty(String context, String namespace, String pod, String container,
                               Consumer<String> onOutput, Runnable onClosed) {
        KubernetesClient client = clientFor(context);
        try {
            // bash first: it has line editing and history (arrows, Ctrl+R); plain sh just echoes ^[[A.
            for (String shell : TTY_SHELLS) {
                ExecAttempt attempt = tryShellTty(client, namespace, pod, container, shell, onOutput, onClosed);
                if (attempt.session() != null) {
                    return attempt.session();
                }
                if (!attempt.shellMissing()) {
                    throw new ClusterException(explainExecFailure(namespace, attempt.reason()));
                }
            }
            throw new ClusterException("This container has no shell (tried "
                    + String.join(", ", TTY_SHELLS) + "). It is probably a distroless or scratch "
                    + "image. Pick another container if the pod has one, or debug it from a terminal with: "
                    + "kubectl debug -it " + pod + " -n " + namespace + " --image=busybox"
                    + (container != null ? " --target=" + container : ""));
        } catch (ClusterException ce) {
            client.close();
            throw ce;
        } catch (Exception e) {
            client.close();
            throw new ClusterException(explainExecFailure(namespace, rootMessage(e)), e);
        }
    }

    /** TTY shells, bash first: it has line editing and history; plain sh (dash) just echoes ^[[A. */
    private static final String[] TTY_SHELLS = {"/bin/bash", "/bin/sh", "/busybox/sh"};

    /** Shells tried in order: most images have /bin/sh; distroless :debug images have /busybox/sh. */
    private static final String[] EXEC_SHELLS = {"/bin/sh", "/bin/bash", "/busybox/sh"};

    /** Echoed by the shell to prove the session is really live (we have no TTY, hence no prompt). */
    private static final String EXEC_READY = "__kubedesk_ready__";

    /** Outcome of one shell attempt: a live session, or why it failed. */
    private record ExecAttempt(ExecSession session, String reason, boolean shellMissing) { }

    /**
     * Start {@code shell} in the container and wait until it echoes a marker back — the only reliable
     * "connected" signal without a TTY. Output is held back until then, so a failed attempt never reaches
     * the UI and the marker is stripped. On failure the client is left open for the next attempt.
     */
    private ExecAttempt tryShell(KubernetesClient client, String namespace, String pod, String container,
                                 String shell, Consumer<String> onOutput, Runnable onClosed) throws Exception {
        CompletableFuture<String> ready = new CompletableFuture<>(); // null = live; text = failure reason
        AtomicBoolean live = new AtomicBoolean(false);
        AtomicBoolean missing = new AtomicBoolean(false);
        StringBuilder held = new StringBuilder();

        OutputStream uiOut = new OutputStream() {
            @Override
            public void write(int b) {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                String s = new String(b, off, len, StandardCharsets.UTF_8);
                synchronized (held) {
                    if (live.get()) {
                        onOutput.accept(s);
                        return;
                    }
                    held.append(s);
                    int i = held.indexOf(EXEC_READY);
                    if (i >= 0) {
                        String before = held.substring(0, i);
                        String after = held.substring(i + EXEC_READY.length()).replaceFirst("^\\r?\\n", "");
                        held.setLength(0);
                        live.set(true);
                        if (!before.isBlank()) {
                            onOutput.accept(before);
                        }
                        if (!after.isEmpty()) {
                            onOutput.accept(after);
                        }
                        ready.complete(null);
                    }
                }
            }
        };

        ExecWatch watch = client.pods().inNamespace(namespace).withName(pod)
                .inContainer(container)
                .redirectingInput()
                .writingOutput(uiOut)
                .writingError(uiOut)
                .usingListener(new ExecListener() {
                    @Override
                    public void onFailure(Throwable t, Response response) {
                        if (live.get()) {
                            onOutput.accept("\n[connection lost: " + rootMessage(t) + "]\n");
                            onClosed.run();
                            return;
                        }
                        String msg = rootMessage(t);
                        if (response != null) {
                            msg = "HTTP " + response.code() + " — " + msg;
                            try {
                                String body = response.body();
                                if (body != null && !body.isBlank()) {
                                    msg += " — " + body.strip();
                                }
                            } catch (Exception ignored) {
                                // body is best-effort detail
                            }
                        }
                        ready.complete(msg);
                    }

                    @Override
                    public void onExit(int code, io.fabric8.kubernetes.api.model.Status status) {
                        String detail = status != null && status.getMessage() != null ? status.getMessage() : "";
                        if (live.get()) {
                            if (code != 0) {
                                onOutput.accept("\n[shell exited with code " + code + "]\n");
                            }
                            return;
                        }
                        String lower = detail.toLowerCase();
                        // containerd says "executable file not found"/"no such file"; dockershim just
                        // reports 126/127 (cannot execute / not found).
                        if (lower.contains("no such file") || lower.contains("executable file not found")
                                || code == 126 || code == 127) {
                            missing.set(true);
                        }
                        ready.complete("exited with code " + code + (detail.isEmpty() ? "" : ": " + detail));
                    }

                    @Override
                    public void onClose(int code, String reason) {
                        if (live.get()) {
                            onClosed.run();
                        } else {
                            ready.complete("connection closed"
                                    + (reason != null && !reason.isBlank() ? ": " + reason : ""));
                        }
                    }
                })
                .exec(shell);

        OutputStream stdin = watch.getInput();
        try {
            stdin.write(("echo " + EXEC_READY + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            // The exit/failure callback will say why.
        }

        String failure;
        try {
            failure = ready.get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            failure = "the shell did not answer within 20 seconds";
        }
        if (failure != null) {
            watch.close();
            return new ExecAttempt(null, failure, missing.get());
        }

        ExecSession session = new ExecSession() {
            @Override
            public void send(String input) {
                try {
                    stdin.write(input.getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                } catch (IOException e) {
                    onOutput.accept("\n[could not send input: " + e.getMessage() + "]\n");
                }
            }

            @Override
            public String shell() {
                return shell;
            }

            @Override
            public void close() {
                try {
                    watch.close();
                } finally {
                    client.close();
                }
            }
        };
        return new ExecAttempt(session, null, false);
    }

    /**
     * Start {@code shell} with a TTY. Unlike {@link #tryShell} there is no marker handshake: a real
     * terminal prints its own prompt as soon as it starts, so we hand the session over the moment the
     * WebSocket is up. Output is a single merged stream (a PTY combines stdout and stderr) read from
     * {@code getOutput()}, decoded to UTF-8 and forwarded to the UI. If the shell doesn't exist, the
     * exit listener flags it so the next shell is tried; everything else (a live PTY) is returned.
     */
    private ExecAttempt tryShellTty(KubernetesClient client, String namespace, String pod, String container,
                                    String shell, Consumer<String> onOutput, Runnable onClosed) throws Exception {
        CompletableFuture<String> failed = new CompletableFuture<>();
        AtomicBoolean missing = new AtomicBoolean(false);
        // Fires onClosed exactly once even when onExit and onClose both fire.
        final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);

        // TTY requires an explicit output sink (fabric8 rejects the op otherwise). Route it through a
        // simple OutputStream that forwards decoded UTF-8 to the UI callback.
        OutputStream uiOut = new OutputStream() {
            private final java.nio.charset.CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
            private byte[] carry = new byte[0]; // tail of a multi-byte char split across writes

            @Override
            public void write(int b) {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                byte[] all = new byte[carry.length + len];
                System.arraycopy(carry, 0, all, 0, carry.length);
                System.arraycopy(b, off, all, carry.length, len);
                java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(all);
                java.nio.CharBuffer out = java.nio.CharBuffer.allocate(all.length + 1);
                dec.decode(in, out, false); // not end-of-input: an incomplete tail stays in 'in'
                carry = new byte[in.remaining()];
                in.get(carry);
                out.flip();
                if (out.hasRemaining()) {
                    onOutput.accept(out.toString());
                }
            }
        };

        // redirectingInput() is what wires getInput() to the WebSocket: without it fabric8 leaves the
        // input stream null and every keystroke silently vanishes. It must come first — it is the top
        // of the TtyExec* fluent chain on ContainerResource.
        ExecWatch watch = client.pods().inNamespace(namespace).withName(pod)
                .inContainer(container)
                .redirectingInput()
                .writingOutput(uiOut)
                .writingError(uiOut)
                .withTTY()
                .usingListener(new ExecListener() {
                    @Override
                    public void onFailure(Throwable t, ExecListener.Response response) {
                        if (!failed.isDone()) {
                            failed.complete(rootMessage(t));
                        }
                    }

                    @Override
                    public void onExit(int code, io.fabric8.kubernetes.api.model.Status status) {
                        String detail = status != null && status.getMessage() != null ? status.getMessage() : "";
                        String lower = detail.toLowerCase();
                        if (lower.contains("no such file") || lower.contains("executable file not found")
                                || code == 126 || code == 127) {
                            missing.set(true);
                        }
                        if (!failed.isDone()) {
                            failed.complete("exited with code " + code + (detail.isEmpty() ? "" : ": " + detail));
                        } else if (closed.compareAndSet(false, true)) {
                            // Live session: the shell exited. Surface it and end the session once.
                            if (code != 0) {
                                onOutput.accept("\n[shell exited with code " + code + "]\n");
                            }
                            onClosed.run();
                        }
                    }

                    @Override
                    public void onClose(int code, String reason) {
                        if (!failed.isDone()) {
                            failed.complete("connection closed"
                                    + (reason != null && !reason.isBlank() ? ": " + reason : ""));
                        } else if (closed.compareAndSet(false, true)) {
                            onClosed.run();
                        }
                    }
                })
                .exec(shell);

        // A PTY has no handshake: the moment exec() returns the WebSocket is up. But we still want to
        // know whether the shell *exists* — so we wait a beat for an early "not found" exit. If one
        // arrives within 3s we treat it as a shell miss and try the next one. Otherwise hand it over.
        // Output is streamed to the UI by fabric8 via the uiOut sink above.
        boolean shellMissingEarly;
        try {
            String early = failed.get(2, TimeUnit.SECONDS);
            watch.close();
            shellMissingEarly = true;
            // Failed fast; the loop will try the next shell.
        } catch (java.util.concurrent.TimeoutException te) {
            shellMissingEarly = false; // No early exit — the PTY is live (or still starting).
        }
        if (shellMissingEarly) {
            return new ExecAttempt(null, failed.join(), missing.get());
        }

        ExecSession session = new ExecSession() {
            @Override
            public void send(String input) {
                sendRaw(input.getBytes(StandardCharsets.UTF_8));
            }

            private final AtomicBoolean reportedNoInput = new AtomicBoolean(false);
            // Writes go through one background thread: a big paste must not block the UI thread, and
            // a single thread keeps keystrokes in order.
            private final java.util.concurrent.ExecutorService inputPool =
                    java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                        Thread th = new Thread(r, "kubedesk-tty-input");
                        th.setDaemon(true);
                        return th;
                    });

            @Override
            public void sendRaw(byte[] data) {
                try {
                    inputPool.execute(() -> writeInput(data));
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    // session already closed
                }
            }

            private void writeInput(byte[] data) {
                try {
                    OutputStream stdin = watch.getInput();
                    if (stdin == null) {
                        // Surface it once so a silent null is visible; don't spam per keystroke.
                        if (reportedNoInput.compareAndSet(false, true)) {
                            onOutput.accept("\n[! no input stream on this session - keystrokes are being dropped. "
                                    + "Reconnect.]\n");
                        }
                        return;
                    }
                    stdin.write(data);
                    stdin.flush();
                } catch (IOException e) {
                    onOutput.accept("\n[could not send input: " + e.getMessage() + "]\n");
                } catch (NullPointerException e) {
                    if (reportedNoInput.compareAndSet(false, true)) {
                        onOutput.accept("\n[! input stream is null - keystrokes are being dropped. Reconnect.]\n");
                    }
                }
            }

            @Override
            public void resize(int columns, int rows) {
                try {
                    inputPool.execute(() -> {
                        try {
                            watch.resize(columns, rows);
                        } catch (Exception ignored) {
                            // best-effort: a failed resize just means the app may draw a stale size
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    // session already closed
                }
            }

            @Override
            public boolean isTty() {
                return true;
            }

            @Override
            public String shell() {
                return shell;
            }

            @Override
            public void close() {
                inputPool.shutdownNow();
                try {
                    watch.close();
                } finally {
                    client.close();
                }
            }
        };
        return new ExecAttempt(session, null, false);
    }

    /** Turn a raw exec failure into something a user can act on; the raw text is kept as "Details". */
    static String explainExecFailure(String namespace, String raw) {
        String r = raw == null ? "" : raw;
        int junk = r.indexOf(". Received status:"); // fabric8 appends a full Status dump
        if (junk > 0) {
            r = r.substring(0, junk + 1);
        }
        String l = r.toLowerCase();
        String why;
        java.util.regex.Matcher noContainer = java.util.regex.Pattern
                .compile("container (\\S+) not found in pod").matcher(r);
        if (noContainer.find()) {
            why = "This pod has no container named '" + noContainer.group(1) + "'. Pick one from the Container list.";
        } else if (l.contains("403") || l.contains("forbidden")) {
            why = "You're not allowed to open a shell here. RBAC needs the 'create' verb on 'pods/exec' in "
                    + "namespace " + namespace + ". Check with: kubectl auth can-i create pods/exec -n "
                    + namespace + " — or ask your cluster admin.";
        } else if (l.contains("is not valid for pod") || l.contains("container not found")
                || l.contains("not running") || l.contains("completed pod") || l.contains("waiting to start")
                || l.contains("containercreating")) {
            why = "The container isn't running (it may be starting, crash-looping, or the pod has finished). "
                    + "A shell needs a running container. Check the Logs and Events tabs.";
        } else if (l.contains("404") || l.contains("not found")) {
            why = "The pod wasn't found. It may have been replaced; refresh the list and try again.";
        } else if (l.contains("101") || l.contains("upgrade") || l.contains("websocket")
                || l.contains("400") || l.contains("handshake")) {
            why = "The connection couldn't be upgraded to a WebSocket. Shell, port-forward and attach need "
                    + "WebSockets, and a proxy, VPN or load balancer between you and the API server may be "
                    + "blocking them. Check whether 'kubectl exec' works from this machine.";
        } else if (l.contains("timed out") || l.contains("timeout") || l.contains("did not answer")) {
            why = "The shell didn't respond in time. The node or API server may be slow or unreachable.";
        } else {
            why = "Could not open a shell.";
        }
        return why + (r.isBlank() ? "" : "\nDetails: " + r);
    }

    @Override
    public AutoCloseable watchResource(String context, String namespace, ResourceKind kind,
                                       Runnable onChange, Consumer<Exception> onClosed) {
        // A long-lived client backs the watch; both are closed together via the returned handle.
        KubernetesClient client = clientFor(context);
        try {
            Watch watch = switch (kind) {
                case PODS -> client.pods().inNamespace(namespace).watch(triggerWatcher(onChange, onClosed));
                case DEPLOYMENTS -> client.apps().deployments().inNamespace(namespace)
                        .watch(triggerWatcher(onChange, onClosed));
                case REPLICASETS -> client.apps().replicaSets().inNamespace(namespace)
                        .watch(triggerWatcher(onChange, onClosed));
                case STATEFULSETS -> client.apps().statefulSets().inNamespace(namespace)
                        .watch(triggerWatcher(onChange, onClosed));
                case DAEMONSETS -> client.apps().daemonSets().inNamespace(namespace)
                        .watch(triggerWatcher(onChange, onClosed));
                case JOBS -> client.batch().v1().jobs().inNamespace(namespace)
                        .watch(triggerWatcher(onChange, onClosed));
                case CRONJOBS -> client.batch().v1().cronjobs().inNamespace(namespace)
                        .watch(triggerWatcher(onChange, onClosed));
                case SERVICES -> client.services().inNamespace(namespace)
                        .watch(triggerWatcher(onChange, onClosed));
                case INGRESSES -> client.network().v1().ingresses().inNamespace(namespace)
                        .watch(triggerWatcher(onChange, onClosed));
                case GATEWAYCLASSES, GATEWAYS, HTTPROUTES, GRPCROUTES, SEALEDSECRETS ->
                        com.kubedesk.service.crd.CrdSupport.watch(client, kind.crd(), namespace,
                                this.<GenericKubernetesResource>triggerWatcher(onChange, onClosed));
                case CONFIGMAPS -> client.configMaps().inNamespace(namespace)
                        .watch(triggerWatcher(onChange, onClosed));
                case SECRETS -> client.secrets().inNamespace(namespace)
                        .watch(triggerWatcher(onChange, onClosed));
                case PVCS -> client.persistentVolumeClaims().inNamespace(namespace)
                        .watch(triggerWatcher(onChange, onClosed));
                case NODES -> client.nodes().watch(triggerWatcher(onChange, onClosed));
            };
            return () -> {
                try {
                    watch.close();
                } finally {
                    client.close();
                }
            };
        } catch (Exception e) {
            client.close();
            throw new ClusterException("Could not watch " + kind.label() + ": " + rootMessage(e), e);
        }
    }

    /** A watcher that simply signals "something changed" — the UI reacts by re-listing. */
    private <T> Watcher<T> triggerWatcher(Runnable onChange, Consumer<Exception> onClosed) {
        return new Watcher<>() {
            @Override
            public void eventReceived(Action action, T resource) {
                onChange.run();
            }

            @Override
            public void onClose(WatcherException cause) {
                // cause == null means we closed it deliberately; non-null means it dropped.
                if (cause != null) {
                    onClosed.accept(cause);
                }
            }
        };
    }

    @Override
    public List<String> listPodContainers(String context, String namespace, String pod) {
        try (KubernetesClient client = clientFor(context)) {
            Pod p = client.pods().inNamespace(namespace).withName(pod).get();
            if (p == null || p.getSpec() == null || p.getSpec().getContainers() == null) {
                return List.of();
            }
            return p.getSpec().getContainers().stream()
                    .map(c -> c.getName())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new ClusterException("Could not list containers for " + pod + ": " + rootMessage(e), e);
        }
    }

    @Override
    public String podLogs(String context, String namespace, String pod, String container, int tailLines,
                          boolean previous, boolean timestamps) {
        try (KubernetesClient client = clientFor(context)) {
            // PodResource (and inContainer's ContainerResource) support timestamps + the previous
            // (terminated) instance; apply those options before narrowing to tailingLines().
            TimestampBytesLimitTerminateTimeTailPrettyLoggable base =
                    (container == null || container.isBlank())
                            ? client.pods().inNamespace(namespace).withName(pod)
                            : client.pods().inNamespace(namespace).withName(pod).inContainer(container);
            BytesLimitTerminateTimeTailPrettyLoggable withTs = timestamps ? base.usingTimestamps() : base;
            TimeTailPrettyLoggable withPrev = previous ? withTs.terminated() : withTs;
            String log = withPrev.tailingLines(tailLines).getLog();
            return (log == null || log.isEmpty()) ? "(no log output)" : log;
        } catch (Exception e) {
            // A container that never restarted has no previous instance: the API answers 400
            // "previous terminated container ... not found". That's an expected state, not an error;
            // return empty so the UI shows its friendly "has not restarted yet" message.
            if (previous && rootMessage(e).toLowerCase().contains("previous terminated container")) {
                return "";
            }
            throw new ClusterException("Could not load logs for " + pod + ": " + rootMessage(e), e);
        }
    }

    @Override
    public AutoCloseable followLogs(String context, String namespace, String pod, String container,
                                    int tailLines, boolean timestamps,
                                    Consumer<String> onChunk, Consumer<Exception> onClosed) {
        // A long-lived client backs the stream; both are closed together via the returned handle.
        KubernetesClient client = clientFor(context);
        try {
            TimestampBytesLimitTerminateTimeTailPrettyLoggable base =
                    (container == null || container.isBlank())
                            ? client.pods().inNamespace(namespace).withName(pod)
                            : client.pods().inNamespace(namespace).withName(pod).inContainer(container);
            BytesLimitTerminateTimeTailPrettyLoggable withTs = timestamps ? base.usingTimestamps() : base;
            LogWatch watch = withTs.tailingLines(tailLines).watchLog();
            InputStream in = watch.getOutput();
            // fabric8 hands us an InputStream; pump it on a daemon thread so the FX thread is free.
            Thread reader = new Thread(() -> {
                byte[] buf = new byte[8192];
                try {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (n > 0) {
                            onChunk.accept(new String(buf, 0, n, StandardCharsets.UTF_8));
                        }
                    }
                    onClosed.accept(null); // clean EOF (container ended / stream closed)
                } catch (Exception e) {
                    onClosed.accept(e);
                }
            }, "kubedesk-logs-" + pod);
            reader.setDaemon(true);
            reader.start();
            return () -> {
                try {
                    watch.close();
                } finally {
                    client.close();
                }
            };
        } catch (Exception e) {
            client.close();
            throw new ClusterException("Could not stream logs for " + pod + ": " + rootMessage(e), e);
        }
    }

    @Override
    public void deleteResource(String context, String namespace, ResourceKind kind, String name) {
        try (KubernetesClient client = clientFor(context)) {
            switch (kind) {
                case PODS -> client.pods().inNamespace(namespace).withName(name).delete();
                case DEPLOYMENTS -> client.apps().deployments().inNamespace(namespace).withName(name).delete();
                case REPLICASETS -> client.apps().replicaSets().inNamespace(namespace).withName(name).delete();
                case STATEFULSETS -> client.apps().statefulSets().inNamespace(namespace).withName(name).delete();
                case DAEMONSETS -> client.apps().daemonSets().inNamespace(namespace).withName(name).delete();
                case JOBS -> client.batch().v1().jobs().inNamespace(namespace).withName(name).delete();
                case CRONJOBS -> client.batch().v1().cronjobs().inNamespace(namespace).withName(name).delete();
                case SERVICES -> client.services().inNamespace(namespace).withName(name).delete();
                case INGRESSES -> client.network().v1().ingresses().inNamespace(namespace).withName(name).delete();
                case GATEWAYCLASSES, GATEWAYS, HTTPROUTES, GRPCROUTES, SEALEDSECRETS ->
                        throw new ClusterException(kind.label() + " are read-only in KubeDesk.");
                case CONFIGMAPS -> client.configMaps().inNamespace(namespace).withName(name).delete();
                case SECRETS -> client.secrets().inNamespace(namespace).withName(name).delete();
                case PVCS -> client.persistentVolumeClaims().inNamespace(namespace).withName(name).delete();
                case NODES -> client.nodes().withName(name).delete();
            }
        } catch (Exception e) {
            throw new ClusterException("Could not delete " + name + ": " + rootMessage(e), e);
        }
    }

    @Override
    public int cleanupPods(String context, String namespace, java.util.Set<String> phases, boolean dryRun) {
        try (KubernetesClient client = clientFor(context)) {
            int count = 0;
            for (Pod p : client.pods().inNamespace(namespace).list().getItems()) {
                String phase = p.getStatus() != null ? p.getStatus().getPhase() : null;
                if (phase == null || !phases.contains(phase)) {
                    continue;
                }
                if (p.getMetadata().getDeletionTimestamp() != null) {
                    continue; // already being deleted
                }
                count++;
                if (!dryRun) {
                    client.pods().inNamespace(namespace).withName(p.getMetadata().getName()).delete();
                }
            }
            return count;
        } catch (Exception e) {
            throw new ClusterException("Pod cleanup failed: " + rootMessage(e), e);
        }
    }

    @Override
    public void restartDeployment(String context, String namespace, String name) {
        try (KubernetesClient client = clientFor(context)) {
            client.apps().deployments().inNamespace(namespace).withName(name).rolling().restart();
        } catch (Exception e) {
            throw new ClusterException("Could not restart " + name + ": " + rootMessage(e), e);
        }
    }

    @Override
    public String restartPod(String context, String namespace, String name) {
        try (KubernetesClient client = clientFor(context)) {
            Pod pod = client.pods().inNamespace(namespace).withName(name).get();
            if (pod == null) {
                throw new ClusterException("Pod " + name + " no longer exists.");
            }
            String owner = controlledBy(pod);
            if ("-".equals(owner)) {
                throw new ClusterException("Pod " + name + " is not managed by a controller, so deleting it "
                        + "would not recreate it. Use Delete if you really want it gone.");
            }
            client.pods().inNamespace(namespace).withName(name).delete();
            return owner;
        } catch (ClusterException e) {
            throw e;
        } catch (Exception e) {
            throw new ClusterException("Could not restart " + name + ": " + rootMessage(e), e);
        }
    }

    @Override
    public void scaleDeployment(String context, String namespace, String name, int replicas) {
        try (KubernetesClient client = clientFor(context)) {
            client.apps().deployments().inNamespace(namespace).withName(name).scale(replicas);
        } catch (Exception e) {
            throw new ClusterException("Could not scale " + name + ": " + rootMessage(e), e);
        }
    }

    @Override
    public Integer getDeploymentReplicas(String context, String namespace, String name) {
        try (KubernetesClient client = clientFor(context)) {
            Deployment d = client.apps().deployments().inNamespace(namespace).withName(name).get();
            if (d == null || d.getSpec() == null) {
                return null;
            }
            return d.getSpec().getReplicas();
        } catch (Exception e) {
            throw new ClusterException("Could not read replicas for " + name + ": " + rootMessage(e), e);
        }
    }

    @Override
    public void applyYaml(String context, String namespace, String yaml) {
        try (KubernetesClient client = clientFor(context)) {
            HasMetadata obj = (HasMetadata) io.fabric8.kubernetes.client.utils.Serialization.unmarshal(yaml);
            serverSideApply(client, obj, namespace, false);
        } catch (Exception e) {
            throw new ClusterException("Could not apply changes: " + rootMessage(e), e);
        }
    }

    @Override
    public Dtos.SealedSecretCert fetchSealedSecretCert(String context, String controllerNamespace,
                                                       String controllerName) {
        try (KubernetesClient client = clientFor(context)) {
            // Build an ordered, de-duplicated candidate list (keyed by "ns/name"): the explicit name
            // first, then common install names, then anything discovered on the cluster.
            LinkedHashMap<String, String[]> candidates = new LinkedHashMap<>();
            java.util.function.BiConsumer<String, String> add = (ns, nm) -> {
                if (ns != null && !ns.isBlank() && nm != null && !nm.isBlank()) {
                    candidates.putIfAbsent(ns.trim() + "/" + nm.trim(), new String[]{ns.trim(), nm.trim()});
                }
            };
            add.accept(controllerNamespace, controllerName);
            for (String ns : new String[]{"kube-system", "sealed-secrets", "sealed-secrets-system"}) {
                for (String nm : new String[]{"sealed-secrets-controller", "sealed-secrets"}) {
                    add.accept(ns, nm);
                }
            }
            discoverSealedSecretsServices(client, add);

            StringBuilder tried = new StringBuilder();
            for (String[] c : candidates.values()) {
                try {
                    String pem = fetchCertFromService(client, c[0], c[1]);
                    if (pem != null && pem.contains("BEGIN CERTIFICATE")) {
                        return new Dtos.SealedSecretCert(c[0], c[1], pem);
                    }
                    tried.append("\n  • ").append(c[0]).append('/').append(c[1]).append(" — no certificate");
                } catch (Exception e) {
                    tried.append("\n  • ").append(c[0]).append('/').append(c[1]).append(" — ").append(rootMessage(e));
                }
            }
            // Suggest the matching filter for the user's shell (findstr on Windows cmd/PowerShell, grep elsewhere).
            boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
            String findSvc = windows
                    ? "kubectl get svc -A | findstr /i sealed"
                    : "kubectl get svc -A | grep -i sealed";
            String hint = candidates.size() <= 6
                    ? "\n\n(Couldn't auto-discover the controller Service — listing Services may be denied "
                      + "for this user. Find it with:  " + findSvc + ")"
                    : "";
            throw new ClusterException("Couldn't find the Sealed Secrets controller certificate. Tried:"
                    + tried + hint + "\n\nOpen Advanced to set the controller's Service name/namespace and "
                    + "Re-fetch, or paste the cert (kubeseal --fetch-cert).");
        } catch (ClusterException e) {
            throw e;
        } catch (Exception e) {
            throw new ClusterException("Couldn't fetch the Sealed Secrets certificate: " + rootMessage(e), e);
        }
    }

    /** Add any Services that look like the Sealed Secrets controller (by well-known labels, then by name). */
    private void discoverSealedSecretsServices(KubernetesClient client,
                                               java.util.function.BiConsumer<String, String> add) {
        String[][] labels = {
                {"app.kubernetes.io/name", "sealed-secrets"},
                {"name", "sealed-secrets-controller"},
                {"app.kubernetes.io/instance", "sealed-secrets"},
        };
        for (String[] lbl : labels) {
            try {
                for (Service s : client.services().inAnyNamespace().withLabel(lbl[0], lbl[1]).list().getItems()) {
                    add.accept(s.getMetadata().getNamespace(), s.getMetadata().getName());
                }
            } catch (Exception ignore) {
                // listing may be forbidden — keep trying other signals
            }
        }
        // Last resort: scan all Services for a "sealed" name match.
        try {
            for (Service s : client.services().inAnyNamespace().list().getItems()) {
                String n = s.getMetadata().getName();
                if (n != null && n.toLowerCase().contains("sealed")) {
                    add.accept(s.getMetadata().getNamespace(), n);
                }
            }
        } catch (Exception ignore) {
            // cluster-wide Service listing denied — the static candidates above still apply
        }
    }

    /**
     * Fetch {@code /v1/cert.pem} from a controller Service via the API-server proxy (the same route as
     * {@code kubeseal --fetch-cert}), reusing the kubeconfig's auth + TLS. Returns the PEM body.
     *
     * <p>The service's port is looked up and used explicitly — an empty port ({@code http:name:}) fails on
     * some clusters with "no endpoints available", so we try the port by name, then by number, then empty.
     */
    private String fetchCertFromService(KubernetesClient client, String ns, String name) throws Exception {
        // Build the list of port references to try, preferring the Service's real "http" port.
        List<String> portRefs = new ArrayList<>();
        try {
            Service svc = client.services().inNamespace(ns).withName(name).get();
            if (svc == null) {
                throw new ClusterException("no such Service");
            }
            if (svc.getSpec() != null && svc.getSpec().getPorts() != null) {
                ServicePort chosen = svc.getSpec().getPorts().stream()
                        .filter(p -> "http".equalsIgnoreCase(p.getName()))
                        .findFirst()
                        .orElse(svc.getSpec().getPorts().isEmpty() ? null : svc.getSpec().getPorts().get(0));
                if (chosen != null) {
                    if (chosen.getName() != null && !chosen.getName().isBlank()) {
                        portRefs.add(chosen.getName());
                    }
                    if (chosen.getPort() != null) {
                        portRefs.add(String.valueOf(chosen.getPort()));
                    }
                }
            }
        } catch (ClusterException ce) {
            throw ce;
        } catch (Exception ignore) {
            // can't read the Service (RBAC) — fall back to well-known values below
        }
        portRefs.add("8080"); // sealed-secrets controller default
        portRefs.add("");      // last resort: let the proxy choose
        // de-duplicate, preserve order
        portRefs = portRefs.stream().distinct().collect(Collectors.toList());

        String base = client.getMasterUrl().toString();
        if (!base.endsWith("/")) {
            base += "/";
        }
        var http = client.getHttpClient();
        Exception last = null;
        for (String portRef : portRefs) {
            String path = "api/v1/namespaces/" + ns + "/services/http:" + name + ":" + portRef
                    + "/proxy/v1/cert.pem";
            try {
                var request = http.newHttpRequestBuilder().uri(base + path).build();
                var response = http.sendAsync(request, String.class)
                        .get(15, java.util.concurrent.TimeUnit.SECONDS);
                if (response.isSuccessful() && response.body() != null
                        && response.body().contains("BEGIN CERTIFICATE")) {
                    return response.body();
                }
                last = new ClusterException("HTTP " + response.code());
            } catch (Exception e) {
                last = e;
            }
        }
        throw (last != null ? last : new ClusterException("no response"));
    }

    @Override
    public Dtos.SecretContent readSecret(String context, String namespace, String name) {
        try (KubernetesClient client = clientFor(context)) {
            Secret secret = client.secrets().inNamespace(namespace).withName(name).get();
            if (secret == null) {
                throw new ClusterException("Secret " + name + " not found in " + namespace + ".");
            }
            // fabric8 returns data values still base64-encoded; decode to raw bytes for re-sealing.
            Map<String, byte[]> decoded = new LinkedHashMap<>();
            Map<String, String> data = secret.getData();
            if (data != null) {
                for (Map.Entry<String, String> e : data.entrySet()) {
                    decoded.put(e.getKey(), Base64.getDecoder().decode(e.getValue()));
                }
            }
            // stringData (if any survived) is already plain text.
            if (secret.getStringData() != null) {
                for (Map.Entry<String, String> e : secret.getStringData().entrySet()) {
                    decoded.put(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8));
                }
            }
            String type = secret.getType() != null ? secret.getType() : "Opaque";
            return new Dtos.SecretContent(name, namespace, type, decoded);
        } catch (ClusterException e) {
            throw e;
        } catch (Exception e) {
            throw new ClusterException("Could not read Secret " + name + ": " + rootMessage(e), e);
        }
    }

    @Override
    public Dtos.SecretContent parseSecretYaml(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new ClusterException("Paste a Secret manifest first.");
        }
        try {
            Object obj = io.fabric8.kubernetes.client.utils.Serialization.unmarshal(yaml);
            if (!(obj instanceof Secret sec)) {
                String kind = (obj instanceof HasMetadata hm && hm.getKind() != null) ? hm.getKind() : "that document";
                throw new ClusterException("Expected a Secret, but got " + kind + " (kind must be Secret).");
            }
            String name = sec.getMetadata() != null ? sec.getMetadata().getName() : null;
            String ns = sec.getMetadata() != null ? sec.getMetadata().getNamespace() : null;
            String type = sec.getType() != null ? sec.getType() : "Opaque";
            Map<String, byte[]> data = new LinkedHashMap<>();
            if (sec.getData() != null) {
                for (Map.Entry<String, String> e : sec.getData().entrySet()) {
                    data.put(e.getKey(), Base64.getDecoder().decode(e.getValue()));
                }
            }
            if (sec.getStringData() != null) {
                for (Map.Entry<String, String> e : sec.getStringData().entrySet()) {
                    data.put(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8));
                }
            }
            if (name == null || name.isBlank()) {
                throw new ClusterException("The Secret is missing metadata.name.");
            }
            return new Dtos.SecretContent(name, ns, type, data);
        } catch (ClusterException e) {
            throw e;
        } catch (Exception e) {
            throw new ClusterException("Couldn't parse the Secret YAML: " + rootMessage(e), e);
        }
    }

    @Override
    public String applyManifests(String context, String defaultNamespace, String yaml) {
        return applyOrValidate(context, defaultNamespace, yaml, false);
    }

    @Override
    public String validateManifests(String context, String defaultNamespace, String yaml) {
        return applyOrValidate(context, defaultNamespace, yaml, true);
    }

    private String applyOrValidate(String context, String defaultNamespace, String yaml, boolean dryRun) {
        try (KubernetesClient client = clientFor(context)) {
            List<HasMetadata> items = client.load(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))).items();
            if (items.isEmpty()) {
                throw new ClusterException("No Kubernetes resources found in the YAML.", null);
            }
            String verb = dryRun ? "is valid" : "applied";
            StringBuilder sb = new StringBuilder();
            int ok = 0;
            int failed = 0;
            for (HasMetadata item : items) {
                String kind = item.getKind() != null ? item.getKind() : "Resource";
                String name = item.getMetadata() != null ? item.getMetadata().getName() : "?";
                try {
                    HasMetadata result = serverSideApply(client, item, defaultNamespace, dryRun);
                    String ns = result.getMetadata() != null ? result.getMetadata().getNamespace() : null;
                    sb.append("✓ ").append(kind).append('/').append(name)
                            .append(ns != null ? " (namespace " + ns + ")" : "")
                            .append(' ').append(verb).append('\n');
                    ok++;
                } catch (Exception e) {
                    sb.append("✗ ").append(kind).append('/').append(name)
                            .append(": ").append(rootMessage(e)).append('\n');
                    failed++;
                }
            }
            String action = dryRun ? "Validated " : "Applied ";
            String header = action + ok + " of " + items.size() + " resource(s)"
                    + (failed > 0 ? ", " + failed + " failed" : "") + ".\n\n";
            return header + sb;
        } catch (ClusterException ce) {
            throw ce;
        } catch (Exception e) {
            throw new ClusterException("Could not process manifests: " + rootMessage(e), e);
        }
    }

    /**
     * Create-or-update a single resource via server-side apply (or dry-run validate). Strips
     * server-managed metadata (managedFields, resourceVersion, uid, creationTimestamp) so a manifest
     * copied from the YAML view applies cleanly. Uses the resource's own namespace, falling back to
     * {@code defaultNamespace}.
     */
    private HasMetadata serverSideApply(KubernetesClient client, HasMetadata item,
                                        String defaultNamespace, boolean dryRun) {
        if (item.getMetadata() != null) {
            item.getMetadata().setManagedFields(null);
            item.getMetadata().setResourceVersion(null);
            item.getMetadata().setUid(null);
            item.getMetadata().setCreationTimestamp(null);
        }
        String ns = item.getMetadata() != null && item.getMetadata().getNamespace() != null
                ? item.getMetadata().getNamespace() : defaultNamespace;

        PatchContext ctx = new PatchContext.Builder()
                .withPatchType(PatchType.SERVER_SIDE_APPLY)
                .withFieldManager("kubedesk")
                .withForce(true)
                .build();

        // fabric8 ignores the namespace for cluster-scoped kinds, so this is safe for both.
        if (ns != null) {
            var r = client.resource(item).inNamespace(ns);
            return dryRun ? r.dryRun(true).patch(ctx) : r.patch(ctx);
        } else {
            var r = client.resource(item);
            return dryRun ? r.dryRun(true).patch(ctx) : r.patch(ctx);
        }
    }

    @Override
    public ResourceData listEvents(String context, String namespace) {
        try (KubernetesClient client = clientFor(context)) {
            List<Event> events = new ArrayList<>(
                    client.v1().events().inNamespace(namespace).list().getItems());
            events.sort(Comparator.comparing(this::eventTimestamp).reversed()); // most recent first
            List<List<String>> rows = new ArrayList<>();
            for (Event ev : events) {
                String obj = "";
                if (ev.getInvolvedObject() != null) {
                    obj = nz(ev.getInvolvedObject().getKind()) + "/"
                            + nz(ev.getInvolvedObject().getName());
                }
                int count = ev.getCount() != null ? ev.getCount() : 1;
                rows.add(List.of(
                        nz(ev.getType()),
                        nz(ev.getReason()),
                        obj,
                        String.valueOf(count),
                        age(eventTimestamp(ev)),
                        ev.getMessage() != null ? ev.getMessage().trim() : ""));
            }
            return new ResourceData(
                    List.of("Type", "Reason", "Object", "Count", "Last Seen", "Message"), rows);
        } catch (Exception e) {
            throw new ClusterException("Could not list events: " + rootMessage(e), e);
        }
    }

    @Override
    public String describeEvents(String context, String namespace, ResourceKind kind, String name) {
        try (KubernetesClient client = clientFor(context)) {
            StringBuilder sb = new StringBuilder();

            // For pods, lead with container diagnostics — this survives even when events expire.
            if (kind == ResourceKind.PODS) {
                Pod p = client.pods().inNamespace(namespace).withName(name).get();
                if (p != null) {
                    sb.append(podDiagnostics(p)).append('\n');
                }
            }

            String wantKind = involvedKind(kind);
            List<Event> events = (kind.namespaced()
                    ? client.v1().events().inNamespace(namespace).list()
                    : client.v1().events().inAnyNamespace().list()).getItems();

            List<Event> matched = events.stream()
                    .filter(ev -> ev.getInvolvedObject() != null
                            && name.equals(ev.getInvolvedObject().getName())
                            && (wantKind == null || wantKind.equals(ev.getInvolvedObject().getKind())))
                    .sorted(Comparator.comparing(this::eventTimestamp))
                    .collect(Collectors.toList());

            sb.append("EVENTS\n------\n");
            if (matched.isEmpty()) {
                sb.append("No events found (Kubernetes keeps events for only ~1 hour, so an older "
                        + "failure has likely aged out).\n");
            } else {
                sb.append(String.format("%-9s %-24s %-14s %s%n", "TYPE", "REASON", "AGE", "MESSAGE"));
                for (Event ev : matched) {
                    String type = ev.getType() != null ? ev.getType() : "";
                    String reason = ev.getReason() != null ? ev.getReason() : "";
                    int count = ev.getCount() != null ? ev.getCount() : 1;
                    String age = age(eventTimestamp(ev)) + (count > 1 ? " (x" + count + ")" : "");
                    String message = ev.getMessage() != null ? ev.getMessage().trim() : "";
                    sb.append(String.format("%-9s %-24s %-14s %s%n", type, reason, age, message));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            throw new ClusterException("Could not load events for " + name + ": " + rootMessage(e), e);
        }
    }

    /**
     * Container-level diagnostics straight from the live pod: current state, restart counts, and —
     * crucially — the {@code lastState.terminated} reason/exitCode/message of the most recent crash.
     * This is what explains a red pod when events have already expired.
     */
    private String podDiagnostics(Pod p) {
        StringBuilder d = new StringBuilder();
        d.append("POD STATUS\n----------\n");
        String phase = p.getStatus() != null && p.getStatus().getPhase() != null
                ? p.getStatus().getPhase() : "Unknown";
        d.append("Phase: ").append(phase).append("    Status: ").append(podStatus(p)).append('\n');
        if (p.getStatus() != null && p.getStatus().getReason() != null) {
            d.append("Reason: ").append(p.getStatus().getReason());
            if (p.getStatus().getMessage() != null) {
                d.append(" — ").append(p.getStatus().getMessage().trim());
            }
            d.append('\n');
        }

        if (p.getStatus() != null && p.getStatus().getContainerStatuses() != null) {
            for (ContainerStatus cs : p.getStatus().getContainerStatuses()) {
                d.append("\nContainer: ").append(cs.getName());
                if (cs.getImage() != null) {
                    d.append("   (").append(cs.getImage()).append(')');
                }
                d.append('\n');
                d.append("  Ready: ").append(Boolean.TRUE.equals(cs.getReady()))
                        .append("    Restarts: ")
                        .append(cs.getRestartCount() != null ? cs.getRestartCount() : 0).append('\n');

                var state = cs.getState();
                if (state != null && state.getWaiting() != null) {
                    var w = state.getWaiting();
                    d.append("  State: Waiting — ").append(nz(w.getReason()));
                    if (w.getMessage() != null) {
                        d.append("\n    ").append(w.getMessage().trim());
                    }
                    d.append('\n');
                } else if (state != null && state.getTerminated() != null) {
                    d.append("  State: ").append(formatTerminated(state.getTerminated())).append('\n');
                } else if (state != null && state.getRunning() != null) {
                    d.append("  State: Running (since ").append(nz(state.getRunning().getStartedAt()))
                            .append(")\n");
                }

                // The previous instance's termination — the gold for CrashLoopBackOff.
                if (cs.getLastState() != null && cs.getLastState().getTerminated() != null) {
                    d.append("  Last State: ")
                            .append(formatTerminated(cs.getLastState().getTerminated())).append('\n');
                }
            }
        }
        return d.toString();
    }

    private String formatTerminated(io.fabric8.kubernetes.api.model.ContainerStateTerminated t) {
        StringBuilder s = new StringBuilder("Terminated — reason=").append(nz(t.getReason()));
        if (t.getExitCode() != null) {
            s.append(", exitCode=").append(t.getExitCode()).append(exitCodeHint(t.getExitCode()));
        }
        if (t.getFinishedAt() != null) {
            s.append(", finished=").append(t.getFinishedAt());
        }
        if (t.getMessage() != null && !t.getMessage().isBlank()) {
            s.append("\n    message: ").append(t.getMessage().trim());
        }
        return s.toString();
    }

    /**
     * A short "why it last died" suffix built from the container's previous termination
     * ({@code lastState.terminated}) — the same signal {@link #podDiagnostics} surfaces, so a
     * CrashLoopBackOff finding explains the crash even after the cluster's events have expired.
     * Returns "" when there's no prior termination to report.
     */
    private String crashDetail(ContainerStatus cs) {
        if (cs.getLastState() == null || cs.getLastState().getTerminated() == null) {
            return "";
        }
        var t = cs.getLastState().getTerminated();
        StringBuilder s = new StringBuilder(" — last exit");
        if (t.getExitCode() != null) {
            s.append(' ').append(t.getExitCode()).append(exitCodeHint(t.getExitCode()));
        }
        if (t.getReason() != null && !t.getReason().isBlank()) {
            s.append(" [").append(t.getReason()).append(']');
        }
        return s.toString();
    }

    /**
     * Build the "High restarts" finding with enough context to tell a live crashloop from benign
     * drip: the restart count, the pod's age and the resulting average rate, and the previous
     * instance's exit (code/reason) and how long ago it died. Severity rises to CRITICAL when
     * restarts are frequent (≈1/hour or more on average) <em>and</em> the last one was recent —
     * which separates "16 restarts over 2h, last exit minutes ago" (a pod that just crashlooped)
     * from "16 restarts over 51d" (a long-lived pod that restarts occasionally).
     */
    private HealthFinding highRestartFinding(Pod p, String name, int restarts,
                                             ContainerStatus lastCrashed) {
        StringBuilder detail = new StringBuilder().append(restarts).append(" restarts");
        String created = p.getMetadata() != null ? p.getMetadata().getCreationTimestamp() : null;
        Instant createdAt = parseInstant(created);
        double perHour = -1;
        if (createdAt != null) {
            double hours = Math.max(Duration.between(createdAt, Instant.now()).toSeconds(), 1) / 3600.0;
            perHour = restarts / hours;
            detail.append(" over ").append(age(created)).append(" (~").append(restartRate(perHour)).append(')');
        }
        Instant lastFinish = null;
        if (lastCrashed != null && lastCrashed.getLastState() != null
                && lastCrashed.getLastState().getTerminated() != null) {
            var t = lastCrashed.getLastState().getTerminated();
            lastFinish = parseInstant(t.getFinishedAt());
            detail.append("; last exit");
            if (t.getExitCode() != null) {
                detail.append(' ').append(t.getExitCode()).append(exitCodeHint(t.getExitCode()));
            }
            if (t.getReason() != null && !t.getReason().isBlank()) {
                detail.append(" [").append(t.getReason()).append(']');
            }
            if (t.getFinishedAt() != null && !t.getFinishedAt().isBlank()) {
                detail.append(' ').append(age(t.getFinishedAt())).append(" ago");
            }
        }
        boolean recentlyCrashed = lastFinish != null
                && Duration.between(lastFinish, Instant.now()).toHours() < 6;
        HealthSeverity sev = (perHour >= 1.0 && (lastFinish == null || recentlyCrashed))
                ? HealthSeverity.CRITICAL : HealthSeverity.WARNING;
        return new HealthFinding(sev, ResourceKind.PODS, name, "High restarts", detail.toString());
    }

    /** True when {@code a}'s previous-instance termination is more recent than {@code b}'s. */
    private static boolean laterFinish(ContainerStatus a, ContainerStatus b) {
        String fa = a.getLastState().getTerminated().getFinishedAt();
        String fb = b.getLastState().getTerminated().getFinishedAt();
        if (fa == null) {
            return false;
        }
        if (fb == null) {
            return true;
        }
        return fa.compareTo(fb) > 0; // RFC3339 timestamps sort lexically
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** Compact average restart rate — per hour when frequent, else per day. */
    private static String restartRate(double perHour) {
        if (perHour >= 1.0) {
            return Math.round(perHour) + "/hr";
        }
        double perDay = perHour * 24.0;
        if (perDay >= 0.1) {
            return (Math.round(perDay * 10.0) / 10.0) + "/day";
        }
        return "<0.1/day";
    }

    /** Human hint for common container exit codes. */
    private String exitCodeHint(int code) {
        return switch (code) {
            case 0 -> " (success)";
            case 1 -> " (application error)";
            case 137 -> " (SIGKILL — often OOMKilled or killed by liveness probe)";
            case 139 -> " (SIGSEGV — segmentation fault)";
            case 143 -> " (SIGTERM — graceful shutdown)";
            default -> "";
        };
    }

    private String nz(String s) {
        return s != null ? s : "-";
    }

    /** Best-available timestamp for an event (lastTimestamp, then eventTime, then creation). */
    private String eventTimestamp(Event ev) {
        if (ev.getLastTimestamp() != null) {
            return ev.getLastTimestamp();
        }
        if (ev.getEventTime() != null && ev.getEventTime().getTime() != null) {
            return ev.getEventTime().getTime();
        }
        return ev.getMetadata() != null ? ev.getMetadata().getCreationTimestamp() : null;
    }

    /** Map a resource kind to the Kind string used in an event's involvedObject. */
    private String involvedKind(ResourceKind kind) {
        return switch (kind) {
            case PODS -> "Pod";
            case DEPLOYMENTS -> "Deployment";
            case REPLICASETS -> "ReplicaSet";
            case STATEFULSETS -> "StatefulSet";
            case DAEMONSETS -> "DaemonSet";
            case JOBS -> "Job";
            case CRONJOBS -> "CronJob";
            case SERVICES -> "Service";
            case INGRESSES -> "Ingress";
            case GATEWAYCLASSES, GATEWAYS, HTTPROUTES, GRPCROUTES, SEALEDSECRETS -> kind.crd().kind();
            case CONFIGMAPS -> "ConfigMap";
            case SECRETS -> "Secret";
            case PVCS -> "PersistentVolumeClaim";
            case NODES -> "Node";
        };
    }

    @Override
    public TopologyData getTopology(String context, String namespace) {
        try (KubernetesClient client = clientFor(context)) {
            List<TopoNode> nodes = new ArrayList<>();
            List<TopoEdge> edges = new ArrayList<>();
            java.util.Set<String> nodeIds = new java.util.HashSet<>();

            // Services (tier 1) — remember each selector so we can match workloads.
            Map<String, Map<String, String>> serviceSelectors = new LinkedHashMap<>();
            for (Service s : client.services().inNamespace(namespace).list().getItems()) {
                String id = "Service/" + s.getMetadata().getName();
                String type = s.getSpec() != null && s.getSpec().getType() != null
                        ? s.getSpec().getType() : "";
                nodes.add(new TopoNode(id, "Service", s.getMetadata().getName(), 1, type));
                nodeIds.add(id);
                serviceSelectors.put(id, s.getSpec() != null && s.getSpec().getSelector() != null
                        ? s.getSpec().getSelector() : Map.of());
            }

            // Ingresses (tier 0) — edge to each backend service that exists in this namespace.
            for (Ingress ing : client.network().v1().ingresses().inNamespace(namespace).list().getItems()) {
                String id = "Ingress/" + ing.getMetadata().getName();
                nodes.add(new TopoNode(id, "Ingress", ing.getMetadata().getName(), 0, ingressHosts(ing)));
                for (String svc : ingressBackendServices(ing)) {
                    String sid = "Service/" + svc;
                    if (nodeIds.contains(sid)) {
                        edges.add(new TopoEdge(id, sid));
                    }
                }
            }

            // Gateway API HTTPRoutes (tier 0, like Ingress) — edge to each backend Service. Best-effort:
            // clusters without the CRDs (or without permission) just don't get these nodes.
            try {
                if (com.kubedesk.service.crd.CrdSupport.groupAvailable(client,
                        com.kubedesk.service.crd.GatewayApi.GROUP)) {
                    // Gateways sit in a column before the routes (tier -1 for now; shifted to 0 below).
                    List<GenericKubernetesResource> gateways = com.kubedesk.service.crd.CrdSupport.items(client,
                            com.kubedesk.service.crd.GatewayApi.GATEWAY, namespace);
                    for (GenericKubernetesResource gw : gateways) {
                        nodes.add(new TopoNode("Gateway/" + gw.getMetadata().getName(), "Gateway",
                                gw.getMetadata().getName(), -1,
                                com.kubedesk.service.crd.GatewayApi.GATEWAY.row().apply(gw).get(2)));
                    }
                    for (GenericKubernetesResource route : com.kubedesk.service.crd.CrdSupport.items(client,
                            com.kubedesk.service.crd.GatewayApi.HTTP_ROUTE, namespace)) {
                        String rname = route.getMetadata().getName();
                        String id = "HTTPRoute/" + rname;
                        nodes.add(new TopoNode(id, "HTTPRoute", rname, 0,
                                com.kubedesk.service.crd.GatewayApi.hostnames(route)));
                        for (GenericKubernetesResource gw : gateways) {
                            if (com.kubedesk.service.crd.GatewayApi.routeAttachesTo(route, gw)) {
                                edges.add(new TopoEdge("Gateway/" + gw.getMetadata().getName(), id));
                            }
                        }
                        for (String svc : com.kubedesk.service.crd.GatewayApi.backendServices(route)) {
                            String sid = "Service/" + svc;
                            if (nodeIds.contains(sid)) {
                                edges.add(new TopoEdge(id, sid));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // optional layer
            }

            // Workloads (tier 2) — nodes only; their pods are connected below by ownership.
            for (Deployment d : client.apps().deployments().inNamespace(namespace).list().getItems()) {
                String id = "Deployment/" + d.getMetadata().getName();
                nodes.add(new TopoNode(id, "Deployment", d.getMetadata().getName(), 2,
                        readyText(d.getStatus() != null ? d.getStatus().getReadyReplicas() : null,
                                d.getSpec() != null ? d.getSpec().getReplicas() : null)));
                nodeIds.add(id);
            }
            for (StatefulSet s : client.apps().statefulSets().inNamespace(namespace).list().getItems()) {
                String id = "StatefulSet/" + s.getMetadata().getName();
                nodes.add(new TopoNode(id, "StatefulSet", s.getMetadata().getName(), 2,
                        readyText(s.getStatus() != null ? s.getStatus().getReadyReplicas() : null,
                                s.getSpec() != null ? s.getSpec().getReplicas() : null)));
                nodeIds.add(id);
            }
            for (DaemonSet ds : client.apps().daemonSets().inNamespace(namespace).list().getItems()) {
                String id = "DaemonSet/" + ds.getMetadata().getName();
                nodes.add(new TopoNode(id, "DaemonSet", ds.getMetadata().getName(), 2,
                        readyText(ds.getStatus() != null ? ds.getStatus().getNumberReady() : null,
                                ds.getStatus() != null ? ds.getStatus().getDesiredNumberScheduled() : null)));
                nodeIds.add(id);
            }

            // ReplicaSet -> owning Deployment, so pods (owned by a ReplicaSet) map back to a Deployment.
            Map<String, String> rsToDeployment = new HashMap<>();
            for (var rs : client.apps().replicaSets().inNamespace(namespace).list().getItems()) {
                if (rs.getMetadata().getOwnerReferences() != null) {
                    rs.getMetadata().getOwnerReferences().stream()
                            .filter(o -> "Deployment".equals(o.getKind()))
                            .findFirst()
                            .ifPresent(o -> rsToDeployment.put(rs.getMetadata().getName(), o.getName()));
                }
            }

            // Pods (tier 3) with the TRUE edges:
            //   Service -> Pod  (label selector), and  Workload -> Pod  (ownership).
            for (Pod p : client.pods().inNamespace(namespace).list().getItems()) {
                String podName = p.getMetadata().getName();
                String pid = "Pod/" + podName;
                String phase = p.getStatus() != null && p.getStatus().getPhase() != null
                        ? p.getStatus().getPhase() : "";
                nodes.add(new TopoNode(pid, "Pod", podName, 3, phase));

                Map<String, String> labels = p.getMetadata().getLabels();
                serviceSelectors.forEach((sid, selector) -> {
                    if (selectorMatches(selector, labels)) {
                        edges.add(new TopoEdge(sid, pid));
                    }
                });

                if (p.getMetadata().getOwnerReferences() != null) {
                    for (var owner : p.getMetadata().getOwnerReferences()) {
                        String wid = null;
                        if ("ReplicaSet".equals(owner.getKind())) {
                            String dep = rsToDeployment.get(owner.getName());
                            if (dep != null) {
                                wid = "Deployment/" + dep;
                            }
                        } else if ("StatefulSet".equals(owner.getKind())
                                || "DaemonSet".equals(owner.getKind())) {
                            wid = owner.getKind() + "/" + owner.getName();
                        }
                        if (wid != null && nodeIds.contains(wid)) {
                            edges.add(new TopoEdge(wid, pid));
                        }
                    }
                }
            }

            // Gateways were added at tier -1; when present, shift everything right by one column so the
            // graph reads Gateway → HTTPRoute/Ingress → Service → Workload → Pod. No Gateways = no extra column.
            if (nodes.stream().anyMatch(n -> n.tier() < 0)) {
                nodes.replaceAll(n -> new TopoNode(n.id(), n.kind(), n.name(), n.tier() + 1, n.subtitle()));
            }
            return new TopologyData(nodes, edges);
        } catch (Exception e) {
            throw new ClusterException("Could not build topology: " + rootMessage(e), e);
        }
    }

    @Override
    public Dtos.WorkloadRef ownerWorkload(String context, String namespace, String podName) {
        try (KubernetesClient client = clientFor(context)) {
            Pod p = client.pods().inNamespace(namespace).withName(podName).get();
            if (p == null || p.getMetadata() == null || p.getMetadata().getOwnerReferences() == null
                    || p.getMetadata().getOwnerReferences().isEmpty()) {
                return null;
            }
            var refs = p.getMetadata().getOwnerReferences();
            var ctrl = refs.stream()
                    .filter(r -> Boolean.TRUE.equals(r.getController()))
                    .findFirst()
                    .orElse(refs.get(0));
            switch (ctrl.getKind()) {
                case "ReplicaSet" -> {
                    // Pods of a Deployment are owned by a ReplicaSet; hop up to the Deployment so we
                    // land on a view the sidebar actually has (there's no ReplicaSet view).
                    var rs = client.apps().replicaSets().inNamespace(namespace)
                            .withName(ctrl.getName()).get();
                    if (rs != null && rs.getMetadata() != null
                            && rs.getMetadata().getOwnerReferences() != null) {
                        var dep = rs.getMetadata().getOwnerReferences().stream()
                                .filter(o -> "Deployment".equals(o.getKind()))
                                .findFirst().orElse(null);
                        if (dep != null) {
                            return new Dtos.WorkloadRef(ResourceKind.DEPLOYMENTS, dep.getName());
                        }
                    }
                    return null; // bare ReplicaSet — nothing browsable to jump to
                }
                case "StatefulSet" -> {
                    return new Dtos.WorkloadRef(ResourceKind.STATEFULSETS, ctrl.getName());
                }
                case "DaemonSet" -> {
                    return new Dtos.WorkloadRef(ResourceKind.DAEMONSETS, ctrl.getName());
                }
                case "Job" -> {
                    return new Dtos.WorkloadRef(ResourceKind.JOBS, ctrl.getName());
                }
                default -> {
                    return null;
                }
            }
        } catch (Exception e) {
            throw new ClusterException("Could not resolve pod owner: " + rootMessage(e), e);
        }
    }

    @Override
    public List<String> servicesForPod(String context, String namespace, String podName) {
        try (KubernetesClient client = clientFor(context)) {
            Pod p = client.pods().inNamespace(namespace).withName(podName).get();
            if (p == null || p.getMetadata() == null) {
                return List.of();
            }
            Map<String, String> labels = p.getMetadata().getLabels();
            List<String> out = new ArrayList<>();
            for (Service s : client.services().inNamespace(namespace).list().getItems()) {
                Map<String, String> sel = s.getSpec() != null ? s.getSpec().getSelector() : null;
                if (sel != null && !sel.isEmpty() && selectorMatches(sel, labels)) {
                    out.add(s.getMetadata().getName());
                }
            }
            return out;
        } catch (Exception e) {
            throw new ClusterException("Could not match services: " + rootMessage(e), e);
        }
    }

    @Override
    public List<String> podsForWorkload(String context, String namespace, ResourceKind kind, String name) {
        try (KubernetesClient client = clientFor(context)) {
            Map<String, String> selector = switch (kind) {
                case DEPLOYMENTS -> {
                    var d = client.apps().deployments().inNamespace(namespace).withName(name).get();
                    yield d != null && d.getSpec() != null && d.getSpec().getSelector() != null
                            ? d.getSpec().getSelector().getMatchLabels() : null;
                }
                case REPLICASETS -> {
                    var rs = client.apps().replicaSets().inNamespace(namespace).withName(name).get();
                    yield rs != null && rs.getSpec() != null && rs.getSpec().getSelector() != null
                            ? rs.getSpec().getSelector().getMatchLabels() : null;
                }
                case STATEFULSETS -> {
                    var s = client.apps().statefulSets().inNamespace(namespace).withName(name).get();
                    yield s != null && s.getSpec() != null && s.getSpec().getSelector() != null
                            ? s.getSpec().getSelector().getMatchLabels() : null;
                }
                case DAEMONSETS -> {
                    var ds = client.apps().daemonSets().inNamespace(namespace).withName(name).get();
                    yield ds != null && ds.getSpec() != null && ds.getSpec().getSelector() != null
                            ? ds.getSpec().getSelector().getMatchLabels() : null;
                }
                case JOBS -> {
                    var j = client.batch().v1().jobs().inNamespace(namespace).withName(name).get();
                    yield j != null && j.getSpec() != null && j.getSpec().getSelector() != null
                            ? j.getSpec().getSelector().getMatchLabels() : null;
                }
                default -> null;
            };
            if (selector == null || selector.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (Pod p : client.pods().inNamespace(namespace).list().getItems()) {
                Map<String, String> labels = p.getMetadata() != null ? p.getMetadata().getLabels() : null;
                if (selectorMatches(selector, labels)) {
                    out.add(p.getMetadata().getName());
                }
            }
            return out;
        } catch (Exception e) {
            throw new ClusterException("Could not list workload pods: " + rootMessage(e), e);
        }
    }

    @Override
    public List<HealthFinding> healthCheck(String context, String namespace) {
        try (KubernetesClient client = clientFor(context)) {
            List<HealthFinding> f = new ArrayList<>();

            for (Pod p : client.pods().inNamespace(namespace).list().getItems()) {
                checkPod(p, f);
            }
            for (Deployment d : client.apps().deployments().inNamespace(namespace).list().getItems()) {
                checkReplicas(f, ResourceKind.DEPLOYMENTS, d.getMetadata().getName(),
                        d.getStatus() != null ? d.getStatus().getReadyReplicas() : null,
                        d.getSpec() != null ? d.getSpec().getReplicas() : null);
            }
            for (StatefulSet s : client.apps().statefulSets().inNamespace(namespace).list().getItems()) {
                checkReplicas(f, ResourceKind.STATEFULSETS, s.getMetadata().getName(),
                        s.getStatus() != null ? s.getStatus().getReadyReplicas() : null,
                        s.getSpec() != null ? s.getSpec().getReplicas() : null);
            }
            for (DaemonSet ds : client.apps().daemonSets().inNamespace(namespace).list().getItems()) {
                checkReplicas(f, ResourceKind.DAEMONSETS, ds.getMetadata().getName(),
                        ds.getStatus() != null ? ds.getStatus().getNumberReady() : null,
                        ds.getStatus() != null ? ds.getStatus().getDesiredNumberScheduled() : null);
            }
            for (Job j : client.batch().v1().jobs().inNamespace(namespace).list().getItems()) {
                if ("Failed".equals(jobStatus(j))) {
                    f.add(new HealthFinding(HealthSeverity.CRITICAL, ResourceKind.JOBS,
                            j.getMetadata().getName(), "Failed", "Job did not complete successfully"));
                }
            }
            for (CronJob cj : client.batch().v1().cronjobs().inNamespace(namespace).list().getItems()) {
                if (cj.getSpec() != null && Boolean.TRUE.equals(cj.getSpec().getSuspend())) {
                    f.add(new HealthFinding(HealthSeverity.WARNING, ResourceKind.CRONJOBS,
                            cj.getMetadata().getName(), "Suspended",
                            "spec.suspend is true — no new jobs will be scheduled"));
                }
            }
            for (PersistentVolumeClaim pvc : client.persistentVolumeClaims().inNamespace(namespace)
                    .list().getItems()) {
                String phase = pvc.getStatus() != null ? pvc.getStatus().getPhase() : null;
                if (phase != null && !"Bound".equals(phase)) {
                    HealthSeverity sev = "Lost".equals(phase) ? HealthSeverity.CRITICAL : HealthSeverity.WARNING;
                    f.add(new HealthFinding(sev, ResourceKind.PVCS, pvc.getMetadata().getName(),
                            phase, "PersistentVolumeClaim is not Bound"));
                }
            }
            checkOrphanServices(client, namespace, f);
            checkNodes(client, f);

            // Most severe first, then by kind and name for stability.
            f.sort(Comparator.comparingInt((HealthFinding h) ->
                            h.severity() == HealthSeverity.CRITICAL ? 0 : 1)
                    .thenComparing(h -> h.kind().label())
                    .thenComparing(HealthFinding::name));
            return f;
        } catch (Exception e) {
            throw new ClusterException("Health scan failed: " + rootMessage(e), e);
        }
    }

    private static final java.util.Set<String> BAD_WAITING = java.util.Set.of(
            "CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull", "ErrImageNeverPull",
            "InvalidImageName", "CreateContainerConfigError", "CreateContainerError", "RunContainerError");

    private void checkPod(Pod p, List<HealthFinding> f) {
        var meta = p.getMetadata();
        var st = p.getStatus();
        String name = meta.getName();
        if (meta.getDeletionTimestamp() != null) {
            return; // terminating — don't flag
        }
        if (st != null && "Evicted".equals(st.getReason())) {
            f.add(new HealthFinding(HealthSeverity.CRITICAL, ResourceKind.PODS, name, "Evicted",
                    nz(st.getMessage())));
            return;
        }
        if (st != null && st.getContainerStatuses() != null) {
            for (ContainerStatus cs : st.getContainerStatuses()) {
                if (cs.getState() != null && cs.getState().getWaiting() != null) {
                    String r = cs.getState().getWaiting().getReason();
                    if (r != null && BAD_WAITING.contains(r)) {
                        f.add(new HealthFinding(HealthSeverity.CRITICAL, ResourceKind.PODS, name, r,
                                cs.getName() + ": " + nz(cs.getState().getWaiting().getMessage())
                                        + crashDetail(cs)));
                        return;
                    }
                }
            }
        }
        String phase = st != null ? st.getPhase() : null;
        if ("Pending".equals(phase)) {
            String unsched = podScheduledFalse(p);
            if (unsched != null) {
                f.add(new HealthFinding(HealthSeverity.CRITICAL, ResourceKind.PODS, name,
                        "Unschedulable", unsched));
            } else {
                f.add(new HealthFinding(HealthSeverity.WARNING, ResourceKind.PODS, name,
                        "Pending", "Not running yet"));
            }
            return;
        }
        if ("Failed".equals(phase)) {
            f.add(new HealthFinding(HealthSeverity.CRITICAL, ResourceKind.PODS, name, "Failed",
                    nz(st.getReason())));
            return;
        }
        // Running, but a container process is up yet not Ready (e.g. a failing readiness probe): the
        // pod is "up" but taking no traffic. Phase/restart checks miss this, so flag it explicitly.
        if ("Running".equals(phase) && st.getContainerStatuses() != null) {
            for (ContainerStatus cs : st.getContainerStatuses()) {
                if (cs.getState() != null && cs.getState().getRunning() != null
                        && !Boolean.TRUE.equals(cs.getReady())) {
                    f.add(new HealthFinding(HealthSeverity.WARNING, ResourceKind.PODS, name, "Not ready",
                            cs.getName() + ": running but not Ready (readiness probe failing?)"));
                    break;
                }
            }
        }
        int restarts = 0;
        ContainerStatus lastCrashed = null; // container whose previous instance died most recently
        if (st != null && st.getContainerStatuses() != null) {
            for (ContainerStatus cs : st.getContainerStatuses()) {
                if (cs.getRestartCount() != null) {
                    restarts += cs.getRestartCount();
                }
                if (cs.getLastState() != null && cs.getLastState().getTerminated() != null
                        && (lastCrashed == null || laterFinish(cs, lastCrashed))) {
                    lastCrashed = cs;
                }
            }
        }
        if (restarts >= 5) {
            f.add(highRestartFinding(p, name, restarts, lastCrashed));
        }
        if (st != null && "BestEffort".equals(st.getQosClass())) {
            f.add(new HealthFinding(HealthSeverity.WARNING, ResourceKind.PODS, name, "BestEffort QoS",
                    "No requests/limits set — first to be evicted under node pressure"));
        }
    }

    /** If the pod can't be scheduled, return the reason/message; else null. */
    private String podScheduledFalse(Pod p) {
        if (p.getStatus() == null || p.getStatus().getConditions() == null) {
            return null;
        }
        for (var c : p.getStatus().getConditions()) {
            if ("PodScheduled".equals(c.getType()) && "False".equals(c.getStatus())) {
                return c.getMessage() != null ? c.getMessage() : nz(c.getReason());
            }
        }
        return null;
    }

    private void checkReplicas(List<HealthFinding> f, ResourceKind kind, String name,
                               Integer ready, Integer desired) {
        int r = ready != null ? ready : 0;
        int d = desired != null ? desired : 0;
        if (d > 0 && r == 0) {
            f.add(new HealthFinding(HealthSeverity.CRITICAL, kind, name, "0/" + d + " available",
                    "No replicas are ready"));
        } else if (r < d) {
            f.add(new HealthFinding(HealthSeverity.WARNING, kind, name, r + "/" + d + " available",
                    (d - r) + " replica(s) not ready"));
        }
    }

    private void checkOrphanServices(KubernetesClient client, String namespace, List<HealthFinding> f) {
        for (Service s : client.services().inNamespace(namespace).list().getItems()) {
            var spec = s.getSpec();
            if (spec == null || "ExternalName".equals(spec.getType())) {
                continue;
            }
            if (spec.getSelector() == null || spec.getSelector().isEmpty()) {
                continue; // headless / manually-managed endpoints
            }
            String name = s.getMetadata().getName();
            var ep = client.endpoints().inNamespace(namespace).withName(name).get();
            boolean hasAddresses = ep != null && ep.getSubsets() != null && ep.getSubsets().stream()
                    .anyMatch(sub -> sub.getAddresses() != null && !sub.getAddresses().isEmpty());
            if (!hasAddresses) {
                f.add(new HealthFinding(HealthSeverity.WARNING, ResourceKind.SERVICES, name,
                        "No endpoints", "Selector matches no ready pods"));
            }
        }
    }

    private void checkNodes(KubernetesClient client, List<HealthFinding> f) {
        for (Node n : client.nodes().list().getItems()) {
            String name = n.getMetadata().getName();
            if (n.getSpec() != null && Boolean.TRUE.equals(n.getSpec().getUnschedulable())) {
                f.add(new HealthFinding(HealthSeverity.WARNING, ResourceKind.NODES, name,
                        "Cordoned", "Node is marked unschedulable"));
            }
            if (n.getStatus() == null || n.getStatus().getConditions() == null) {
                continue;
            }
            for (NodeCondition c : n.getStatus().getConditions()) {
                if ("Ready".equals(c.getType()) && !"True".equals(c.getStatus())) {
                    f.add(new HealthFinding(HealthSeverity.CRITICAL, ResourceKind.NODES, name,
                            "NotReady", nz(c.getMessage())));
                } else if (("MemoryPressure".equals(c.getType()) || "DiskPressure".equals(c.getType())
                        || "PIDPressure".equals(c.getType())) && "True".equals(c.getStatus())) {
                    f.add(new HealthFinding(HealthSeverity.WARNING, ResourceKind.NODES, name,
                            c.getType(), nz(c.getMessage())));
                }
            }
        }
    }

    /** True when every selector entry is present with an equal value in the labels. */
    private boolean selectorMatches(Map<String, String> selector, Map<String, String> labels) {
        if (selector == null || selector.isEmpty() || labels == null) {
            return false;
        }
        for (Map.Entry<String, String> e : selector.entrySet()) {
            if (!e.getValue().equals(labels.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private String readyText(Integer ready, Integer desired) {
        return (ready != null ? ready : 0) + "/" + (desired != null ? desired : 0) + " ready";
    }

    private String ingressHosts(Ingress ing) {
        if (ing.getSpec() == null || ing.getSpec().getRules() == null || ing.getSpec().getRules().isEmpty()) {
            return "*";
        }
        String hosts = ing.getSpec().getRules().stream()
                .map(IngressRule::getHost)
                .filter(h -> h != null && !h.isBlank())
                .distinct()
                .collect(Collectors.joining(", "));
        return hosts.isBlank() ? "*" : hosts;
    }

    private java.util.Set<String> ingressBackendServices(Ingress ing) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (ing.getSpec() == null) {
            return out;
        }
        if (ing.getSpec().getDefaultBackend() != null
                && ing.getSpec().getDefaultBackend().getService() != null) {
            out.add(ing.getSpec().getDefaultBackend().getService().getName());
        }
        if (ing.getSpec().getRules() != null) {
            ing.getSpec().getRules().forEach(rule -> {
                if (rule.getHttp() != null && rule.getHttp().getPaths() != null) {
                    rule.getHttp().getPaths().forEach(path -> {
                        if (path.getBackend() != null && path.getBackend().getService() != null) {
                            out.add(path.getBackend().getService().getName());
                        }
                    });
                }
            });
        }
        return out;
    }

    // --- per-kind table builders ------------------------------------------------------------

    private ResourceData pods(KubernetesClient client, String namespace) {
        List<String> columns = List.of("Name", "Ready", "Status", "Restarts", "CPU", "Memory",
                "Image", "Node", "IP", "QoS", "Controlled By", "Age");
        Map<String, String[]> usage = podUsage(client, namespace);
        List<List<String>> rows = new ArrayList<>();
        for (Pod p : client.pods().inNamespace(namespace).list().getItems()) {
            int ready = 0, totalC = 0, restarts = 0;
            if (p.getStatus() != null && p.getStatus().getContainerStatuses() != null) {
                for (ContainerStatus cs : p.getStatus().getContainerStatuses()) {
                    totalC++;
                    if (Boolean.TRUE.equals(cs.getReady())) {
                        ready++;
                    }
                    if (cs.getRestartCount() != null) {
                        restarts += cs.getRestartCount();
                    }
                }
            }
            String node = p.getSpec() != null && p.getSpec().getNodeName() != null
                    ? p.getSpec().getNodeName() : "-";
            String images = p.getSpec() != null ? imagesOf(p.getSpec().getContainers()) : "-";
            String[] u = usage.getOrDefault(p.getMetadata().getName(), new String[]{"-", "-"});
            String ip = p.getStatus() != null && p.getStatus().getPodIP() != null
                    ? p.getStatus().getPodIP() : "-";
            String qos = p.getStatus() != null && p.getStatus().getQosClass() != null
                    ? p.getStatus().getQosClass() : "-";
            rows.add(List.of(
                    p.getMetadata().getName(),
                    ready + "/" + totalC,
                    podStatus(p),
                    String.valueOf(restarts),
                    u[0],
                    u[1],
                    images,
                    node,
                    ip,
                    qos,
                    controlledBy(p),
                    age(p.getMetadata().getCreationTimestamp())));
        }
        return new ResourceData(columns, rows);
    }

    /** The owning controller as "Kind/name" (e.g. ReplicaSet/foo-abc123), or "-" for a bare pod. */
    private String controlledBy(Pod p) {
        var refs = p.getMetadata() != null ? p.getMetadata().getOwnerReferences() : null;
        if (refs == null || refs.isEmpty()) {
            return "-";
        }
        var ctrl = refs.stream()
                .filter(r -> Boolean.TRUE.equals(r.getController()))
                .findFirst()
                .orElse(refs.get(0));
        return ctrl.getKind() + "/" + ctrl.getName();
    }

    /**
     * Live CPU/memory usage per pod from the Metrics API (metrics.k8s.io). Returns podName -> [cpu, mem].
     * If metrics-server isn't installed, returns an empty map and the columns fall back to "-".
     */
    @Override
    public MetricSample podUsageSample(String context, String namespace, String pod) {
        try (KubernetesClient client = clientFor(context)) {
            var pm = client.top().pods().metrics(namespace, pod);
            if (pm == null || pm.getContainers() == null) {
                return null;
            }
            BigDecimal cpu = BigDecimal.ZERO;
            BigDecimal mem = BigDecimal.ZERO;
            for (var c : pm.getContainers()) {
                if (c.getUsage() != null) {
                    Quantity cq = c.getUsage().get("cpu");
                    Quantity mq = c.getUsage().get("memory");
                    if (cq != null) {
                        cpu = cpu.add(Quantity.getAmountInBytes(cq));
                    }
                    if (mq != null) {
                        mem = mem.add(Quantity.getAmountInBytes(mq));
                    }
                }
            }
            double millicores = cpu.multiply(BigDecimal.valueOf(1000)).doubleValue();
            double mib = mem.divide(BigDecimal.valueOf(1024L * 1024L), 2, RoundingMode.HALF_UP)
                    .doubleValue();
            return new MetricSample(millicores, mib);
        } catch (Exception e) {
            return null; // metrics-server unavailable or pod has no metrics yet
        }
    }

    private record PodRequests(double cpu, double mem, int scheduled, long atMs) { }

    private static final long POD_REQUESTS_TTL_MS = 60_000;
    private final Map<String, PodRequests> podRequestsCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public Dtos.ClusterCapacity clusterCapacity(String context) {
        try (KubernetesClient client = clientFor(context)) {
            // Allocatable (what the scheduler may place) from each node's status.
            double cpuAlloc = 0, memAlloc = 0;
            int podsAlloc = 0, nodeCount = 0;
            for (Node n : client.nodes().list().getItems()) {
                nodeCount++;
                Map<String, Quantity> alloc = n.getStatus() != null ? n.getStatus().getAllocatable() : null;
                if (alloc != null) {
                    cpuAlloc += millicores(alloc.get("cpu"));
                    memAlloc += mib(alloc.get("memory"));
                    Quantity pods = alloc.get("pods");
                    if (pods != null) {
                        podsAlloc += (int) Quantity.getAmountInBytes(pods).doubleValue();
                    }
                }
            }

            // Requests + scheduled-pod count from the non-terminal pods across all namespaces.
            // A namespace-scoped token may be unable to list cluster-wide — then leave these at 0.
            // The cluster-wide pod list is the expensive part, and requests change slowly, so reuse
            // it for a minute — the 5–30s usage sampling then only costs a nodes + metrics call.
            double cpuReq = 0, memReq = 0;
            int scheduled = 0;
            PodRequests cachedReq = podRequestsCache.get(context);
            if (cachedReq != null && System.currentTimeMillis() - cachedReq.atMs() < POD_REQUESTS_TTL_MS) {
                cpuReq = cachedReq.cpu();
                memReq = cachedReq.mem();
                scheduled = cachedReq.scheduled();
            } else try {
                for (Pod pod : client.pods().inAnyNamespace().list().getItems()) {
                    String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : null;
                    if ("Succeeded".equals(phase) || "Failed".equals(phase)) {
                        continue; // terminal pods hold no scheduled capacity
                    }
                    scheduled++;
                    if (pod.getSpec() == null || pod.getSpec().getContainers() == null) {
                        continue;
                    }
                    for (var c : pod.getSpec().getContainers()) {
                        if (c.getResources() == null || c.getResources().getRequests() == null) {
                            continue;
                        }
                        cpuReq += millicores(c.getResources().getRequests().get("cpu"));
                        memReq += mib(c.getResources().getRequests().get("memory"));
                    }
                }
                podRequestsCache.put(context,
                        new PodRequests(cpuReq, memReq, scheduled, System.currentTimeMillis()));
            } catch (Exception ignore) {
                // cluster-wide pod list not permitted — requests/scheduled stay 0
            }

            // Live usage from the Metrics API (metrics.k8s.io); absent if metrics-server isn't installed.
            double cpuUsed = 0, memUsed = 0;
            boolean metrics = false;
            try {
                var list = client.top().nodes().metrics();
                if (list != null && list.getItems() != null && !list.getItems().isEmpty()) {
                    for (var nm : list.getItems()) {
                        if (nm.getUsage() != null) {
                            cpuUsed += millicores(nm.getUsage().get("cpu"));
                            memUsed += mib(nm.getUsage().get("memory"));
                        }
                    }
                    metrics = true;
                }
            } catch (Exception ignore) {
                // metrics-server unavailable — leave usage at 0 and flag it
            }

            return new Dtos.ClusterCapacity(nodeCount, cpuReq, cpuUsed, cpuAlloc,
                    memReq, memUsed, memAlloc, scheduled, podsAlloc, metrics);
        }
    }

    /** A CPU {@link Quantity} (cores) as millicores; 0 if null. */
    private static double millicores(Quantity q) {
        return q == null ? 0 : Quantity.getAmountInBytes(q).multiply(BigDecimal.valueOf(1000)).doubleValue();
    }

    /** A memory {@link Quantity} (bytes) as MiB; 0 if null. */
    private static double mib(Quantity q) {
        return q == null ? 0 : Quantity.getAmountInBytes(q)
                .divide(BigDecimal.valueOf(1024L * 1024L), 2, RoundingMode.HALF_UP).doubleValue();
    }

    private Map<String, String[]> podUsage(KubernetesClient client, String namespace) {
        Map<String, String[]> map = new HashMap<>();
        try {
            var list = client.top().pods().metrics(namespace);
            if (list == null || list.getItems() == null) {
                return map;
            }
            for (var pm : list.getItems()) {
                BigDecimal cpu = BigDecimal.ZERO;
                BigDecimal mem = BigDecimal.ZERO;
                if (pm.getContainers() != null) {
                    for (var c : pm.getContainers()) {
                        if (c.getUsage() != null) {
                            Quantity cq = c.getUsage().get("cpu");
                            Quantity mq = c.getUsage().get("memory");
                            if (cq != null) {
                                cpu = cpu.add(Quantity.getAmountInBytes(cq));
                            }
                            if (mq != null) {
                                mem = mem.add(Quantity.getAmountInBytes(mq));
                            }
                        }
                    }
                }
                if (pm.getMetadata() != null && pm.getMetadata().getName() != null) {
                    map.put(pm.getMetadata().getName(),
                            new String[]{formatCpu(cpu), formatMem(mem)});
                }
            }
        } catch (Exception e) {
            // Metrics API unavailable (metrics-server not installed) — leave usage blank.
        }
        return map;
    }

    /** CPU cores -> millicores string, e.g. 0.012 -> "12m". */
    private String formatCpu(BigDecimal cores) {
        return cores.multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.HALF_UP)
                .toPlainString() + "m";
    }

    /** Memory bytes -> mebibytes string, e.g. 47185920 -> "45Mi". */
    private String formatMem(BigDecimal bytes) {
        return bytes.divide(BigDecimal.valueOf(1024L * 1024L), 0, RoundingMode.HALF_UP)
                .toPlainString() + "Mi";
    }

    /**
     * Kubectl-style pod status: surfaces the meaningful reason (CrashLoopBackOff, ImagePullBackOff,
     * OOMKilled, Error, …) from container state rather than just the high-level phase. This is what
     * turns the red chip into something actionable.
     */
    private String podStatus(Pod p) {
        if (p.getMetadata() != null && p.getMetadata().getDeletionTimestamp() != null) {
            return "Terminating";
        }
        var status = p.getStatus();
        if (status == null) {
            return "Unknown";
        }
        // A container stuck waiting (image pull, config error, crash loop) is the most useful signal.
        if (status.getContainerStatuses() != null) {
            for (ContainerStatus cs : status.getContainerStatuses()) {
                if (cs.getState() != null && cs.getState().getWaiting() != null
                        && cs.getState().getWaiting().getReason() != null) {
                    return cs.getState().getWaiting().getReason();
                }
            }
            // Otherwise, a container that terminated abnormally (Error, OOMKilled, …).
            for (ContainerStatus cs : status.getContainerStatuses()) {
                if (cs.getState() != null && cs.getState().getTerminated() != null) {
                    var term = cs.getState().getTerminated();
                    if (term.getReason() != null && !"Completed".equals(term.getReason())) {
                        return term.getReason();
                    }
                }
            }
        }
        return status.getReason() != null ? status.getReason()
                : (status.getPhase() != null ? status.getPhase() : "Unknown");
    }

    /** Comma-joined, de-duplicated container images (each includes its :tag version). */
    private String imagesOf(List<io.fabric8.kubernetes.api.model.Container> containers) {
        if (containers == null || containers.isEmpty()) {
            return "-";
        }
        String images = containers.stream()
                .map(io.fabric8.kubernetes.api.model.Container::getImage)
                .filter(img -> img != null && !img.isBlank())
                .distinct()
                .collect(Collectors.joining(", "));
        return images.isBlank() ? "-" : images;
    }

    private ResourceData deployments(KubernetesClient client, String namespace) {
        List<String> columns = List.of("Name", "Ready", "Up-to-date", "Available", "Image", "Age");
        List<List<String>> rows = new ArrayList<>();
        for (Deployment d : client.apps().deployments().inNamespace(namespace).list().getItems()) {
            var st = d.getStatus();
            var spec = d.getSpec();
            int desired = spec != null && spec.getReplicas() != null ? spec.getReplicas() : 0;
            int ready = st != null && st.getReadyReplicas() != null ? st.getReadyReplicas() : 0;
            int updated = st != null && st.getUpdatedReplicas() != null ? st.getUpdatedReplicas() : 0;
            int available = st != null && st.getAvailableReplicas() != null ? st.getAvailableReplicas() : 0;
            String images = spec != null && spec.getTemplate() != null && spec.getTemplate().getSpec() != null
                    ? imagesOf(spec.getTemplate().getSpec().getContainers()) : "-";
            rows.add(List.of(
                    d.getMetadata().getName(),
                    ready + "/" + desired,
                    String.valueOf(updated),
                    String.valueOf(available),
                    images,
                    age(d.getMetadata().getCreationTimestamp())));
        }
        return new ResourceData(columns, rows);
    }

    /**
     * ReplicaSets, with the owning Deployment and its rollout revision — so the list doubles as a
     * revision history (old ReplicaSets scaled to 0 are the previous rollouts).
     */
    private ResourceData replicaSets(KubernetesClient client, String namespace) {
        List<String> columns = List.of("Name", "Desired", "Current", "Ready", "Owner", "Revision", "Image", "Age");
        List<List<String>> rows = new ArrayList<>();
        for (ReplicaSet r : client.apps().replicaSets().inNamespace(namespace).list().getItems()) {
            var spec = r.getSpec();
            var st = r.getStatus();
            int desired = spec != null && spec.getReplicas() != null ? spec.getReplicas() : 0;
            int current = st != null && st.getReplicas() != null ? st.getReplicas() : 0;
            int ready = st != null && st.getReadyReplicas() != null ? st.getReadyReplicas() : 0;
            String owner = r.getMetadata().getOwnerReferences() == null ? "-"
                    : r.getMetadata().getOwnerReferences().stream()
                    .map(o -> o.getKind() + "/" + o.getName()).findFirst().orElse("-");
            Map<String, String> ann = r.getMetadata().getAnnotations();
            String revision = ann != null ? ann.getOrDefault(REVISION_ANNOTATION, "-") : "-";
            String images = spec != null && spec.getTemplate() != null && spec.getTemplate().getSpec() != null
                    ? imagesOf(spec.getTemplate().getSpec().getContainers()) : "-";
            rows.add(List.of(r.getMetadata().getName(), String.valueOf(desired), String.valueOf(current),
                    String.valueOf(ready), owner, revision, images,
                    age(r.getMetadata().getCreationTimestamp())));
        }
        return new ResourceData(columns, rows);
    }

    static final String REVISION_ANNOTATION = "deployment.kubernetes.io/revision";

    private ResourceData statefulSets(KubernetesClient client, String namespace) {
        List<String> columns = List.of("Name", "Ready", "Image", "Age");
        List<List<String>> rows = new ArrayList<>();
        for (StatefulSet s : client.apps().statefulSets().inNamespace(namespace).list().getItems()) {
            var spec = s.getSpec();
            var st = s.getStatus();
            int desired = spec != null && spec.getReplicas() != null ? spec.getReplicas() : 0;
            int ready = st != null && st.getReadyReplicas() != null ? st.getReadyReplicas() : 0;
            String images = spec != null && spec.getTemplate() != null && spec.getTemplate().getSpec() != null
                    ? imagesOf(spec.getTemplate().getSpec().getContainers()) : "-";
            rows.add(List.of(s.getMetadata().getName(), ready + "/" + desired, images,
                    age(s.getMetadata().getCreationTimestamp())));
        }
        return new ResourceData(columns, rows);
    }

    private ResourceData daemonSets(KubernetesClient client, String namespace) {
        List<String> columns = List.of("Name", "Desired", "Ready", "Up-to-date", "Available", "Image", "Age");
        List<List<String>> rows = new ArrayList<>();
        for (DaemonSet d : client.apps().daemonSets().inNamespace(namespace).list().getItems()) {
            var st = d.getStatus();
            int desired = st != null && st.getDesiredNumberScheduled() != null ? st.getDesiredNumberScheduled() : 0;
            int ready = st != null && st.getNumberReady() != null ? st.getNumberReady() : 0;
            int updated = st != null && st.getUpdatedNumberScheduled() != null ? st.getUpdatedNumberScheduled() : 0;
            int available = st != null && st.getNumberAvailable() != null ? st.getNumberAvailable() : 0;
            var spec = d.getSpec();
            String images = spec != null && spec.getTemplate() != null && spec.getTemplate().getSpec() != null
                    ? imagesOf(spec.getTemplate().getSpec().getContainers()) : "-";
            rows.add(List.of(d.getMetadata().getName(), String.valueOf(desired), String.valueOf(ready),
                    String.valueOf(updated), String.valueOf(available), images,
                    age(d.getMetadata().getCreationTimestamp())));
        }
        return new ResourceData(columns, rows);
    }

    private ResourceData jobs(KubernetesClient client, String namespace) {
        List<String> columns = List.of("Name", "Completions", "Status", "Age");
        List<List<String>> rows = new ArrayList<>();
        for (Job j : client.batch().v1().jobs().inNamespace(namespace).list().getItems()) {
            var spec = j.getSpec();
            var st = j.getStatus();
            int completions = spec != null && spec.getCompletions() != null ? spec.getCompletions() : 1;
            int succeeded = st != null && st.getSucceeded() != null ? st.getSucceeded() : 0;
            String status = jobStatus(j);
            rows.add(List.of(j.getMetadata().getName(), succeeded + "/" + completions, status,
                    age(j.getMetadata().getCreationTimestamp())));
        }
        return new ResourceData(columns, rows);
    }

    private String jobStatus(Job j) {
        var st = j.getStatus();
        if (st == null) {
            return "Unknown";
        }
        if (st.getConditions() != null) {
            for (var c : st.getConditions()) {
                if ("Complete".equals(c.getType()) && "True".equals(c.getStatus())) {
                    return "Complete";
                }
                if ("Failed".equals(c.getType()) && "True".equals(c.getStatus())) {
                    return "Failed";
                }
            }
        }
        int active = st.getActive() != null ? st.getActive() : 0;
        return active > 0 ? "Running" : "Pending";
    }

    private ResourceData cronJobs(KubernetesClient client, String namespace) {
        List<String> columns = List.of("Name", "Schedule", "Suspend", "Active", "Last Schedule", "Age");
        List<List<String>> rows = new ArrayList<>();
        for (CronJob c : client.batch().v1().cronjobs().inNamespace(namespace).list().getItems()) {
            var spec = c.getSpec();
            var st = c.getStatus();
            String schedule = spec != null && spec.getSchedule() != null ? spec.getSchedule() : "-";
            String suspend = spec != null && Boolean.TRUE.equals(spec.getSuspend()) ? "True" : "False";
            int active = st != null && st.getActive() != null ? st.getActive().size() : 0;
            String last = st != null && st.getLastScheduleTime() != null
                    ? age(st.getLastScheduleTime()) : "-";
            rows.add(List.of(c.getMetadata().getName(), schedule, suspend, String.valueOf(active), last,
                    age(c.getMetadata().getCreationTimestamp())));
        }
        return new ResourceData(columns, rows);
    }

    private ResourceData configMaps(KubernetesClient client, String namespace) {
        List<String> columns = List.of("Name", "Data", "Age");
        List<List<String>> rows = new ArrayList<>();
        for (ConfigMap cm : client.configMaps().inNamespace(namespace).list().getItems()) {
            int keys = (cm.getData() != null ? cm.getData().size() : 0)
                    + (cm.getBinaryData() != null ? cm.getBinaryData().size() : 0);
            rows.add(List.of(cm.getMetadata().getName(), String.valueOf(keys),
                    age(cm.getMetadata().getCreationTimestamp())));
        }
        return new ResourceData(columns, rows);
    }

    private ResourceData secrets(KubernetesClient client, String namespace) {
        List<String> columns = List.of("Name", "Type", "Data", "Age");
        List<List<String>> rows = new ArrayList<>();
        for (Secret s : client.secrets().inNamespace(namespace).list().getItems()) {
            String type = s.getType() != null ? s.getType() : "Opaque";
            int keys = s.getData() != null ? s.getData().size() : 0;
            rows.add(List.of(s.getMetadata().getName(), type, String.valueOf(keys),
                    age(s.getMetadata().getCreationTimestamp())));
        }
        return new ResourceData(columns, rows);
    }

    private ResourceData pvcs(KubernetesClient client, String namespace) {
        List<String> columns = List.of("Name", "Status", "Volume", "Capacity", "Access Modes", "StorageClass", "Age");
        List<List<String>> rows = new ArrayList<>();
        for (PersistentVolumeClaim pvc : client.persistentVolumeClaims().inNamespace(namespace).list().getItems()) {
            var spec = pvc.getSpec();
            var st = pvc.getStatus();
            String phase = st != null && st.getPhase() != null ? st.getPhase() : "-";
            String volume = spec != null && spec.getVolumeName() != null ? spec.getVolumeName() : "-";
            String capacity = "-";
            if (st != null && st.getCapacity() != null) {
                Quantity q = st.getCapacity().get("storage");
                if (q != null) {
                    capacity = q.getAmount() + (q.getFormat() != null ? q.getFormat() : "");
                }
            }
            String modes = spec != null && spec.getAccessModes() != null
                    ? spec.getAccessModes().stream().map(this::shortAccessMode).collect(Collectors.joining(","))
                    : "-";
            String sc = spec != null && spec.getStorageClassName() != null ? spec.getStorageClassName() : "-";
            rows.add(List.of(pvc.getMetadata().getName(), phase, volume, capacity,
                    modes.isBlank() ? "-" : modes, sc, age(pvc.getMetadata().getCreationTimestamp())));
        }
        return new ResourceData(columns, rows);
    }

    private String shortAccessMode(String mode) {
        return switch (mode) {
            case "ReadWriteOnce" -> "RWO";
            case "ReadOnlyMany" -> "ROX";
            case "ReadWriteMany" -> "RWX";
            case "ReadWriteOncePod" -> "RWOP";
            default -> mode;
        };
    }

    private ResourceData services(KubernetesClient client, String namespace) {
        List<String> columns = List.of("Name", "Type", "Cluster-IP", "Ports", "Age");
        List<List<String>> rows = new ArrayList<>();
        for (Service s : client.services().inNamespace(namespace).list().getItems()) {
            var spec = s.getSpec();
            String type = spec != null && spec.getType() != null ? spec.getType() : "-";
            String clusterIp = spec != null && spec.getClusterIP() != null ? spec.getClusterIP() : "-";
            String ports = "-";
            if (spec != null && spec.getPorts() != null && !spec.getPorts().isEmpty()) {
                ports = spec.getPorts().stream().map(this::formatPort).collect(Collectors.joining(", "));
            }
            rows.add(List.of(
                    s.getMetadata().getName(),
                    type,
                    clusterIp,
                    ports,
                    age(s.getMetadata().getCreationTimestamp())));
        }
        return new ResourceData(columns, rows);
    }

    private ResourceData ingresses(KubernetesClient client, String namespace) {
        List<String> columns = List.of("Name", "Class", "Hosts", "Address", "Ports", "Age");
        List<List<String>> rows = new ArrayList<>();
        for (Ingress ing : client.network().v1().ingresses().inNamespace(namespace).list().getItems()) {
            var spec = ing.getSpec();
            String ingClass = spec != null && spec.getIngressClassName() != null
                    ? spec.getIngressClassName() : "<none>";

            String hosts = "*";
            if (spec != null && spec.getRules() != null && !spec.getRules().isEmpty()) {
                hosts = spec.getRules().stream()
                        .map(IngressRule::getHost)
                        .filter(h -> h != null && !h.isBlank())
                        .distinct()
                        .collect(Collectors.joining(", "));
                if (hosts.isBlank()) {
                    hosts = "*";
                }
            }

            // Ports: 80 always, plus 443 when any TLS block is present.
            boolean hasTls = spec != null && spec.getTls() != null && spec.getTls().stream()
                    .anyMatch(t -> t != null);
            String ports = hasTls ? "80, 443" : "80";

            String address = "-";
            if (ing.getStatus() != null && ing.getStatus().getLoadBalancer() != null
                    && ing.getStatus().getLoadBalancer().getIngress() != null) {
                address = ing.getStatus().getLoadBalancer().getIngress().stream()
                        .map(lb -> lb.getIp() != null && !lb.getIp().isBlank() ? lb.getIp() : lb.getHostname())
                        .filter(a -> a != null && !a.isBlank())
                        .distinct()
                        .collect(Collectors.joining(", "));
                if (address.isBlank()) {
                    address = "-";
                }
            }

            rows.add(List.of(
                    ing.getMetadata().getName(),
                    ingClass,
                    hosts,
                    address,
                    ports,
                    age(ing.getMetadata().getCreationTimestamp())));
        }
        return new ResourceData(columns, rows);
    }

    private ResourceData nodes(KubernetesClient client) {
        List<String> columns = List.of("Name", "Status", "Roles", "CPU", "Mem", "Version");
        List<List<String>> rows = new ArrayList<>();
        Map<String, double[]> usage = nodeUsage(client); // name -> {cpuMillicores, memMiB}; empty if no metrics
        for (Node n : client.nodes().list().getItems()) {
            String name = n.getMetadata().getName();
            String status = "Unknown";
            if (n.getStatus() != null && n.getStatus().getConditions() != null) {
                for (NodeCondition c : n.getStatus().getConditions()) {
                    if ("Ready".equals(c.getType())) {
                        status = "True".equals(c.getStatus()) ? "Ready" : "NotReady";
                    }
                }
            }
            String roles = n.getMetadata().getLabels() == null ? "-" : n.getMetadata().getLabels().keySet().stream()
                    .filter(k -> k.startsWith("node-role.kubernetes.io/"))
                    .map(k -> k.substring("node-role.kubernetes.io/".length()))
                    .filter(r -> !r.isBlank())
                    .collect(Collectors.joining(","));
            if (roles.isBlank()) {
                roles = "<none>";
            }
            // CPU/Mem as a percentage of the node's allocatable, from the Metrics API. "-" if unavailable.
            String cpuPct = "-", memPct = "-";
            double[] u = usage.get(name);
            Map<String, Quantity> alloc = n.getStatus() != null ? n.getStatus().getAllocatable() : null;
            if (u != null && alloc != null) {
                double cpuAlloc = millicores(alloc.get("cpu"));
                double memAlloc = mib(alloc.get("memory"));
                if (cpuAlloc > 0) {
                    cpuPct = Math.round(u[0] / cpuAlloc * 100.0) + "%";
                }
                if (memAlloc > 0) {
                    memPct = Math.round(u[1] / memAlloc * 100.0) + "%";
                }
            }
            String version = n.getStatus() != null && n.getStatus().getNodeInfo() != null
                    ? n.getStatus().getNodeInfo().getKubeletVersion() : "-";
            rows.add(List.of(name, status, roles, cpuPct, memPct, version));
        }
        return new ResourceData(columns, rows);
    }

    /** Live CPU (millicores) and memory (MiB) usage per node from the Metrics API; empty if unavailable. */
    private Map<String, double[]> nodeUsage(KubernetesClient client) {
        Map<String, double[]> map = new HashMap<>();
        try {
            var list = client.top().nodes().metrics();
            if (list != null && list.getItems() != null) {
                for (var nm : list.getItems()) {
                    if (nm.getMetadata() != null && nm.getMetadata().getName() != null && nm.getUsage() != null) {
                        map.put(nm.getMetadata().getName(), new double[]{
                                millicores(nm.getUsage().get("cpu")), mib(nm.getUsage().get("memory"))});
                    }
                }
            }
        } catch (Exception ignore) {
            // metrics-server unavailable — columns fall back to "-"
        }
        return map;
    }

    private String formatPort(ServicePort port) {
        StringBuilder sb = new StringBuilder();
        if (port.getPort() != null) {
            sb.append(port.getPort());
        }
        if (port.getNodePort() != null) {
            sb.append(':').append(port.getNodePort());
        }
        if (port.getProtocol() != null) {
            sb.append('/').append(port.getProtocol());
        }
        return sb.toString();
    }

    // --- Helm releases ----------------------------------------------------------------------

    /** Jackson is already on the classpath via the fabric8 client; reused for Helm payload JSON. */
    private final ObjectMapper helmJson = new ObjectMapper();

    @Override
    public List<HelmRelease> listHelmReleases(String context, String namespace) {
        try (KubernetesClient client = clientFor(context)) {
            var secretsOp = (namespace == null || namespace.isBlank())
                    ? client.secrets().inAnyNamespace()
                    : client.secrets().inNamespace(namespace);
            List<Secret> stored = secretsOp.withLabel("owner", "helm").list().getItems();

            // Pick the highest-revision Secret per (namespace, release) using the cheap Helm labels
            // (name/version), WITHOUT decoding — so we gunzip + parse only one payload per release,
            // not one per historical revision.
            Map<String, Secret> latestSecret = new LinkedHashMap<>();
            Map<String, Integer> latestVer = new HashMap<>();
            for (Secret s : stored) {
                if (s.getMetadata() == null || s.getMetadata().getLabels() == null) {
                    continue;
                }
                Map<String, String> labels = s.getMetadata().getLabels();
                String relName = labels.get("name");
                String verStr = labels.get("version");
                if (relName == null || verStr == null) {
                    continue;
                }
                int ver;
                try {
                    ver = Integer.parseInt(verStr.trim());
                } catch (NumberFormatException nfe) {
                    continue;
                }
                String key = s.getMetadata().getNamespace() + "/" + relName;
                Integer cur = latestVer.get(key);
                if (cur == null || ver > cur) {
                    latestVer.put(key, ver);
                    latestSecret.put(key, s);
                }
            }

            List<HelmRelease> out = new ArrayList<>();
            for (Secret s : latestSecret.values()) {
                HelmRelease r = decodeRelease(s, false);
                if (r != null) {
                    out.add(r);
                }
            }
            out.sort(Comparator.comparing(HelmRelease::namespace).thenComparing(HelmRelease::name));
            return out;
        } catch (Exception e) {
            throw new ClusterException("Could not list Helm releases: " + rootMessage(e), e);
        }
    }

    @Override
    public List<HelmRelease> helmHistory(String context, String namespace, String release) {
        try (KubernetesClient client = clientFor(context)) {
            var secretsOp = (namespace == null || namespace.isBlank())
                    ? client.secrets().inAnyNamespace()
                    : client.secrets().inNamespace(namespace);
            List<Secret> stored = secretsOp
                    .withLabel("owner", "helm")
                    .withLabel("name", release)
                    .list().getItems();

            List<HelmRelease> out = new ArrayList<>();
            for (Secret s : stored) {
                HelmRelease r = decodeRelease(s, true);
                if (r != null) {
                    out.add(r);
                }
            }
            // Newest revision first.
            out.sort(Comparator.comparingInt(HelmRelease::revision).reversed());
            return out;
        } catch (Exception e) {
            throw new ClusterException("Could not read history for Helm release '" + release + "': "
                    + rootMessage(e), e);
        }
    }

    // --- Helm rollback (no CLI) -------------------------------------------------------------

    private static final com.fasterxml.jackson.databind.ObjectMapper YAML =
            new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());

    /** One stored revision: its Secret and the decoded release JSON. */
    private record StoredRelease(Secret secret, com.fasterxml.jackson.databind.node.ObjectNode json) {
        int version() {
            return json.path("version").asInt(0);
        }

        String status() {
            return json.path("info").path("status").asText("");
        }
    }

    /** A manifest object plus how to address it on the API server. */
    private record ManifestObj(String key, String display, ResourceDefinitionContext rdc, String namespace,
                               String name, JsonNode body) {
    }

    @Override
    public Dtos.HelmRollbackResult helmRollback(String context, String namespace, String release,
                                                int toRevision, boolean dryRun) {
        try (KubernetesClient client = clientFor(context)) {
            List<StoredRelease> stored = new ArrayList<>();
            for (Secret s : client.secrets().inNamespace(namespace)
                    .withLabel("owner", "helm").withLabel("name", release).list().getItems()) {
                if (!"helm.sh/release.v1".equals(s.getType()) || s.getData() == null
                        || !s.getData().containsKey("release")) {
                    continue;
                }
                JsonNode n = helmJson.readTree(decodeHelmPayload(s.getData().get("release")));
                if (n instanceof com.fasterxml.jackson.databind.node.ObjectNode on) {
                    stored.add(new StoredRelease(s, on));
                }
            }
            if (stored.isEmpty()) {
                throw new ClusterException("Helm release '" + release + "' not found in " + namespace + ".");
            }
            stored.sort(Comparator.comparingInt(StoredRelease::version));
            StoredRelease latest = stored.get(stored.size() - 1);
            // "Current" is what's deployed now; fall back to the newest record (e.g. after a failed upgrade).
            StoredRelease current = stored.stream().filter(r -> "deployed".equals(r.status()))
                    .reduce((a, b) -> b).orElse(latest);
            StoredRelease target = stored.stream().filter(r -> r.version() == toRevision).findFirst()
                    .orElseThrow(() -> new ClusterException("Revision " + toRevision + " of '" + release
                            + "' isn't stored any more (Helm keeps a limited history)."));
            if (target.version() == current.version() && "deployed".equals(current.status())) {
                throw new ClusterException("'" + release + "' is already running revision " + toRevision + ".");
            }
            int newVersion = latest.version() + 1;

            Map<String, ResourceDefinitionContext> rdcCache = new HashMap<>();
            Map<String, ManifestObj> from = parseManifest(client, namespace,
                    current.json().path("manifest").asText(""), rdcCache);
            Map<String, ManifestObj> to = parseManifest(client, namespace,
                    target.json().path("manifest").asText(""), rdcCache);
            JsonNode hooks = target.json().path("hooks");
            boolean hasHooks = hooks.isArray() && !hooks.isEmpty();

            // Plan: create / patch what the target has, delete what only the current has.
            List<Runnable> actions = new ArrayList<>();
            List<String> created = new ArrayList<>();
            List<String> updated = new ArrayList<>();
            List<String> deleted = new ArrayList<>();
            List<String> kept = new ArrayList<>();
            for (ManifestObj t : to.values()) {
                var op = t.namespace() == null
                        ? client.genericKubernetesResources(t.rdc())
                        : client.genericKubernetesResources(t.rdc()).inNamespace(t.namespace());
                GenericKubernetesResource live = op.withName(t.name()).get();
                if (live == null) {
                    GenericKubernetesResource obj = helmJson.convertValue(t.body(), GenericKubernetesResource.class);
                    created.add(t.display());
                    actions.add(() -> op.resource(obj).dryRun(dryRun).create());
                } else {
                    ManifestObj f = from.get(t.key());
                    JsonNode patch = mergePatch(f != null ? f.body() : helmJson.createObjectNode(), t.body());
                    if (patch.isEmpty()) {
                        // Unchanged between the two revisions — but re-assert it in case it drifted.
                        patch = mergePatch(helmJson.createObjectNode(), t.body());
                        actions.add(patchAction(op, t.name(), patch, dryRun));
                        continue;
                    }
                    updated.add(t.display());
                    actions.add(patchAction(op, t.name(), patch, dryRun));
                }
            }
            for (ManifestObj f : from.values()) {
                if (to.containsKey(f.key())) {
                    continue;
                }
                if ("keep".equals(f.body().path("metadata").path("annotations")
                        .path("helm.sh/resource-policy").asText(""))) {
                    kept.add(f.display());
                    continue;
                }
                var op = f.namespace() == null
                        ? client.genericKubernetesResources(f.rdc())
                        : client.genericKubernetesResources(f.rdc()).inNamespace(f.namespace());
                if (op.withName(f.name()).get() == null) {
                    continue; // already gone
                }
                deleted.add(f.display());
                actions.add(() -> op.withName(f.name()).dryRun(dryRun).delete());
            }

            Dtos.HelmRollbackResult result = new Dtos.HelmRollbackResult(current.version(), toRevision,
                    newVersion, created, updated, deleted, kept, hasHooks);

            if (dryRun) {
                for (Runnable a : actions) {
                    a.run(); // server-side validation only (dryRun=All); nothing persists
                }
                return result;
            }

            // 1. Record the new revision as pending, like Helm does, so a crash leaves a visible trace.
            String now = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                    .format(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
            var rel = target.json().deepCopy();
            rel.put("version", newVersion);
            var info = (com.fasterxml.jackson.databind.node.ObjectNode) rel.with("info");
            info.put("status", "pending-rollback");
            info.put("description", "Rollback to " + toRevision);
            info.put("last_deployed", now);
            info.put("deleted", "");
            JsonNode firstDeployed = current.json().path("info").path("first_deployed");
            if (!firstDeployed.isMissingNode()) {
                info.set("first_deployed", firstDeployed);
            }
            String secretName = "sh.helm.release.v1." + release + ".v" + newVersion;
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("name", release);
            labels.put("owner", "helm");
            labels.put("status", "pending-rollback");
            labels.put("version", String.valueOf(newVersion));
            labels.put("modifiedAt", String.valueOf(Instant.now().getEpochSecond()));
            Secret newSecret = new io.fabric8.kubernetes.api.model.SecretBuilder()
                    .withNewMetadata().withName(secretName).withNamespace(namespace).withLabels(labels).endMetadata()
                    .withType("helm.sh/release.v1")
                    .withData(Map.of("release", encodeHelmPayload(rel)))
                    .build();
            client.secrets().inNamespace(namespace).resource(newSecret).create();

            // 2. Bring the cluster to the target manifest.
            try {
                for (Runnable a : actions) {
                    a.run();
                }
            } catch (RuntimeException e) {
                info.put("status", "failed");
                info.put("description", "Rollback \"" + release + "\" failed: " + rootMessage(e));
                writeReleaseStatus(client, namespace, secretName, rel, "failed");
                throw e;
            }

            // 3. Mark the new revision deployed and supersede whatever was deployed before.
            info.put("status", "deployed");
            writeReleaseStatus(client, namespace, secretName, rel, "deployed");
            for (StoredRelease r : stored) {
                if ("deployed".equals(r.status())) {
                    var old = r.json().deepCopy();
                    ((com.fasterxml.jackson.databind.node.ObjectNode) old.with("info")).put("status", "superseded");
                    writeReleaseStatus(client, namespace, r.secret().getMetadata().getName(), old, "superseded");
                }
            }
            return result;
        } catch (ClusterException ce) {
            throw ce;
        } catch (Exception e) {
            throw new ClusterException((dryRun ? "Rollback check failed for '" : "Rollback failed for '")
                    + release + "': " + rootMessage(e), e);
        }
    }

    private Runnable patchAction(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation<GenericKubernetesResource,
            ?, io.fabric8.kubernetes.client.dsl.Resource<GenericKubernetesResource>> op,
                                 String name, JsonNode patch, boolean dryRun) {
        String body = patch.toString();
        return () -> op.withName(name).dryRun(dryRun).patch(
                io.fabric8.kubernetes.client.dsl.base.PatchContext.of(
                        io.fabric8.kubernetes.client.dsl.base.PatchType.JSON_MERGE), body);
    }

    /** Rewrite a stored release Secret's payload and its status label. */
    private void writeReleaseStatus(KubernetesClient client, String namespace, String secretName,
                                    JsonNode rel, String status) throws IOException {
        String payload = encodeHelmPayload(rel);
        client.secrets().inNamespace(namespace).withName(secretName).edit(s -> {
            s.getData().put("release", payload);
            if (s.getMetadata().getLabels() != null) {
                s.getMetadata().getLabels().put("status", status);
                s.getMetadata().getLabels().put("modifiedAt", String.valueOf(Instant.now().getEpochSecond()));
            }
            return s;
        });
    }

    /** Inverse of {@link #decodeHelmPayload}: gzip the JSON, base64 it (Helm), base64 again (Secret data). */
    private String encodeHelmPayload(JsonNode rel) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bytes)) {
            gz.write(helmJson.writeValueAsBytes(rel));
        }
        String helmB64 = Base64.getEncoder().encodeToString(bytes.toByteArray());
        return Base64.getEncoder().encodeToString(helmB64.getBytes(StandardCharsets.UTF_8));
    }

    /** Split a rendered Helm manifest into addressable objects keyed by group/kind/namespace/name. */
    private Map<String, ManifestObj> parseManifest(KubernetesClient client, String releaseNs, String manifest,
                                                   Map<String, ResourceDefinitionContext> rdcCache)
            throws IOException {
        Map<String, ManifestObj> out = new LinkedHashMap<>();
        if (manifest == null || manifest.isBlank()) {
            return out;
        }
        try (var it = YAML.readerFor(JsonNode.class).<JsonNode>readValues(manifest)) {
            while (it.hasNext()) {
                JsonNode doc = it.next();
                if (doc == null || !doc.isObject() || !doc.hasNonNull("kind") || !doc.hasNonNull("apiVersion")) {
                    continue;
                }
                String apiVersion = doc.get("apiVersion").asText();
                String kind = doc.get("kind").asText();
                String name = doc.path("metadata").path("name").asText("");
                if (name.isEmpty()) {
                    continue;
                }
                ResourceDefinitionContext rdc = rdcCache.computeIfAbsent(apiVersion + "/" + kind,
                        k -> discover(client, apiVersion, kind));
                String ns = null;
                if (rdc.isNamespaceScoped()) {
                    String declared = doc.path("metadata").path("namespace").asText("");
                    ns = declared.isEmpty() ? releaseNs : declared;
                }
                String group = apiVersion.contains("/") ? apiVersion.substring(0, apiVersion.indexOf('/')) : "";
                String key = group + "|" + kind + "|" + (ns == null ? "" : ns) + "|" + name;
                out.put(key, new ManifestObj(key, kind + "/" + name, rdc, ns, name, doc));
            }
        }
        return out;
    }

    /** Resolve plural + scope for an apiVersion/kind via API discovery. */
    private static ResourceDefinitionContext discover(KubernetesClient client, String apiVersion, String kind) {
        var list = client.getApiResources(apiVersion);
        if (list != null && list.getResources() != null) {
            for (var r : list.getResources()) {
                if (kind.equals(r.getKind()) && r.getName() != null && !r.getName().contains("/")) {
                    String group = apiVersion.contains("/") ? apiVersion.substring(0, apiVersion.indexOf('/')) : "";
                    String version = apiVersion.contains("/") ? apiVersion.substring(apiVersion.indexOf('/') + 1)
                            : apiVersion;
                    return new ResourceDefinitionContext.Builder()
                            .withGroup(group).withVersion(version).withKind(kind)
                            .withPlural(r.getName()).withNamespaced(Boolean.TRUE.equals(r.getNamespaced()))
                            .build();
                }
            }
        }
        throw new ClusterException("The cluster doesn't know " + kind + " (" + apiVersion
                + "). Is the CRD installed?");
    }

    /**
     * An RFC 7386 JSON merge patch turning {@code from} into {@code to}: changed/added keys are set,
     * keys only in {@code from} become null (removed), nested objects recurse, arrays are replaced.
     * Applied to the live object this is Helm's 3-way merge: fields others added (e.g. by an HPA or a
     * mutating webhook) survive, fields the chart dropped are removed.
     */
    static JsonNode mergePatch(JsonNode from, JsonNode to) {
        var mapper = new ObjectMapper();
        if (from == null || !from.isObject() || !to.isObject()) {
            return to;
        }
        var p = mapper.createObjectNode();
        from.fieldNames().forEachRemaining(f -> {
            if (!to.has(f)) {
                p.putNull(f);
            }
        });
        to.fields().forEachRemaining(e -> {
            JsonNode fv = from.get(e.getKey());
            JsonNode tv = e.getValue();
            if (fv == null) {
                p.set(e.getKey(), tv);
            } else if (!fv.equals(tv)) {
                if (fv.isObject() && tv.isObject()) {
                    JsonNode sub = mergePatch(fv, tv);
                    if (!sub.isEmpty()) {
                        p.set(e.getKey(), sub);
                    }
                } else {
                    p.set(e.getKey(), tv);
                }
            }
        });
        return p;
    }

    // --- Deployment rollout history -----------------------------------------------------------

    private static final String CHANGE_CAUSE = "kubernetes.io/change-cause";

    @Override
    public List<Dtos.DeploymentRevision> deploymentHistory(String context, String namespace, String name) {
        try (KubernetesClient client = clientFor(context)) {
            Deployment d = client.apps().deployments().inNamespace(namespace).withName(name).get();
            if (d == null) {
                throw new ClusterException("Deployment " + name + " not found.");
            }
            String uid = d.getMetadata().getUid();
            Map<String, String> dAnn = d.getMetadata().getAnnotations();
            String currentRev = dAnn != null ? dAnn.get(REVISION_ANNOTATION) : null;
            List<Dtos.DeploymentRevision> out = new ArrayList<>();
            for (ReplicaSet rs : ownedReplicaSets(client, namespace, uid)) {
                Map<String, String> ann = rs.getMetadata().getAnnotations();
                long rev;
                try {
                    rev = Long.parseLong(ann.get(REVISION_ANNOTATION));
                } catch (Exception e) {
                    continue;
                }
                var tpl = rs.getSpec() != null ? rs.getSpec().getTemplate() : null;
                if (tpl != null && tpl.getMetadata() != null && tpl.getMetadata().getLabels() != null) {
                    tpl.getMetadata().getLabels().remove("pod-template-hash");
                }
                String images = tpl != null && tpl.getSpec() != null ? imagesOf(tpl.getSpec().getContainers()) : "-";
                int pods = rs.getStatus() != null && rs.getStatus().getReplicas() != null
                        ? rs.getStatus().getReplicas() : 0;
                out.add(new Dtos.DeploymentRevision(rev, rs.getMetadata().getName(),
                        ann.getOrDefault(CHANGE_CAUSE, ""), images, pods,
                        age(rs.getMetadata().getCreationTimestamp()),
                        String.valueOf(rev).equals(currentRev),
                        tpl == null ? "" : io.fabric8.kubernetes.client.utils.Serialization.asYaml(tpl)));
            }
            out.sort(Comparator.comparingLong(Dtos.DeploymentRevision::revision).reversed());
            return out;
        } catch (ClusterException ce) {
            throw ce;
        } catch (Exception e) {
            throw new ClusterException("Could not read rollout history of " + name + ": " + rootMessage(e), e);
        }
    }

    @Override
    public void rollbackDeployment(String context, String namespace, String name, long toRevision) {
        try (KubernetesClient client = clientFor(context)) {
            Deployment d = client.apps().deployments().inNamespace(namespace).withName(name).get();
            if (d == null) {
                throw new ClusterException("Deployment " + name + " not found.");
            }
            if (d.getSpec() != null && Boolean.TRUE.equals(d.getSpec().getPaused())) {
                throw new ClusterException(name + " is paused. Resume it before rolling back "
                        + "(kubectl rollout resume deployment/" + name + ").");
            }
            ReplicaSet target = ownedReplicaSets(client, namespace, d.getMetadata().getUid()).stream()
                    .filter(rs -> String.valueOf(toRevision).equals(
                            rs.getMetadata().getAnnotations().get(REVISION_ANNOTATION)))
                    .findFirst()
                    .orElseThrow(() -> new ClusterException("Revision " + toRevision + " of " + name
                            + " not found (old ReplicaSets beyond revisionHistoryLimit are deleted)."));
            var tpl = target.getSpec().getTemplate();
            if (tpl.getMetadata() != null && tpl.getMetadata().getLabels() != null) {
                tpl.getMetadata().getLabels().remove("pod-template-hash");
            }
            String cause = target.getMetadata().getAnnotations().get(CHANGE_CAUSE);
            client.apps().deployments().inNamespace(namespace).withName(name).edit(dep -> {
                dep.getSpec().setTemplate(tpl);
                Map<String, String> ann = dep.getMetadata().getAnnotations();
                if (ann == null) {
                    ann = new HashMap<>();
                    dep.getMetadata().setAnnotations(ann);
                }
                if (cause != null) {
                    ann.put(CHANGE_CAUSE, cause);
                } else {
                    ann.remove(CHANGE_CAUSE);
                }
                return dep;
            });
        } catch (ClusterException ce) {
            throw ce;
        } catch (Exception e) {
            throw new ClusterException("Could not roll back " + name + ": " + rootMessage(e), e);
        }
    }

    /** ReplicaSets whose controller owner is the Deployment with this UID. */
    private static List<ReplicaSet> ownedReplicaSets(KubernetesClient client, String namespace, String uid) {
        List<ReplicaSet> out = new ArrayList<>();
        for (ReplicaSet rs : client.apps().replicaSets().inNamespace(namespace).list().getItems()) {
            var refs = rs.getMetadata().getOwnerReferences();
            if (refs != null && refs.stream().anyMatch(o -> uid.equals(o.getUid()))
                    && rs.getMetadata().getAnnotations() != null) {
                out.add(rs);
            }
        }
        return out;
    }

    /**
     * Decode one Helm storage Secret into a {@link HelmRelease}. Returns {@code null} if the secret is
     * not a Helm release or its payload can't be read (so a stray/corrupt secret never breaks the list).
     * When {@code full} is false, the heavy fields (values/manifest/notes) are left blank.
     */
    private HelmRelease decodeRelease(Secret s, boolean full) {
        try {
            if (s == null || s.getMetadata() == null) {
                return null;
            }
            if (!"helm.sh/release.v1".equals(s.getType())) {
                return null;
            }
            Map<String, String> data = s.getData();
            if (data == null || !data.containsKey("release")) {
                return null;
            }
            JsonNode root = helmJson.readTree(decodeHelmPayload(data.get("release")));

            String name = text(root, "name");
            String ns = text(root, "namespace");
            if (ns.isBlank() && s.getMetadata().getNamespace() != null) {
                ns = s.getMetadata().getNamespace();
            }
            int revision = root.path("version").asInt(0);

            JsonNode info = root.path("info");
            String status = text(info, "status");
            String description = text(info, "description");
            String lastDeployed = text(info, "last_deployed");

            JsonNode chart = root.path("chart").path("metadata");
            String chartName = text(chart, "name");
            String chartVersion = text(chart, "version");
            String appVersion = text(chart, "appVersion");

            String valuesYaml = "";
            String manifestYaml = "";
            String notes = "";
            Map<String, String> valuesFlat = Map.of();
            if (full) {
                JsonNode config = root.path("config");
                boolean noValues = config.isMissingNode() || config.isNull() || config.isEmpty();
                valuesYaml = noValues
                        ? "# (no user-supplied values)\n"
                        : io.fabric8.kubernetes.client.utils.Serialization.asYaml(config);
                manifestYaml = text(root, "manifest");
                notes = text(info, "notes");
                if (!noValues) {
                    Map<String, String> flat = new LinkedHashMap<>();
                    flatten("", config, flat);
                    valuesFlat = flat;
                }
            }
            return new HelmRelease(name, ns, revision, status, chartName, chartVersion, appVersion,
                    age(helmInstant(lastDeployed)), description, valuesYaml, manifestYaml, notes,
                    valuesFlat);
        } catch (Exception e) {
            return null; // skip unreadable/non-Helm secrets rather than failing the whole list
        }
    }

    /**
     * Decode Helm's release payload. There are two base64 layers plus gzip: the Kubernetes API server
     * base64-encodes every Secret {@code data} value (fabric8 hands it back still encoded), and Helm
     * itself stores the release body as {@code base64(gzip(JSON))}. So we base64-decode the k8s layer,
     * base64-decode Helm's layer, gunzip, and the result is the release JSON.
     */
    private byte[] decodeHelmPayload(String stored) throws IOException {
        // fabric8's Secret.getData() returns the API server's base64-encoded values verbatim.
        byte[] k8sDecoded = Base64.getDecoder().decode(stored);
        // That yields Helm's own base64 text of gzip(JSON).
        byte[] gzipped = Base64.getDecoder().decode(new String(k8sDecoded, StandardCharsets.UTF_8).trim());
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            return gz.readAllBytes();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText("");
    }

    /**
     * Normalize a Helm RFC3339 timestamp (which may carry a non-UTC offset such as {@code -07:00},
     * unlike Kubernetes' always-UTC {@code ...Z}) to a UTC instant string that {@code age(...)} can
     * parse. Returns "" if it can't be parsed, so the age renders as "-" rather than crashing.
     */
    private static String helmInstant(String ts) {
        if (ts == null || ts.isBlank()) {
            return "";
        }
        try {
            return java.time.OffsetDateTime.parse(ts).toInstant().toString();
        } catch (RuntimeException e) {
            try {
                return java.time.Instant.parse(ts).toString();
            } catch (RuntimeException e2) {
                return "";
            }
        }
    }

    /**
     * Flatten a Helm values tree to dotted leaf paths: objects become {@code parent.child}, arrays
     * become {@code parent[i]}, and scalars become their text. Used to diff two revisions' settings in
     * plain terms. {@code prefix} is the path accumulated so far ("" at the root).
     */
    private static void flatten(String prefix, JsonNode node, Map<String, String> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.put(prefix, "null");
        } else if (node.isObject()) {
            if (node.isEmpty()) {
                out.put(prefix, "{}");
                return;
            }
            node.fields().forEachRemaining(e -> {
                String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                flatten(key, e.getValue(), out);
            });
        } else if (node.isArray()) {
            if (node.isEmpty()) {
                out.put(prefix, "[]");
                return;
            }
            for (int i = 0; i < node.size(); i++) {
                flatten(prefix + "[" + i + "]", node.get(i), out);
            }
        } else {
            out.put(prefix, node.asText(""));
        }
    }

    // --- helpers ----------------------------------------------------------------------------

    /** One cached client per context; the key carries the kubeconfig identity so edits invalidate it. */
    private record CachedClient(String key, KubernetesClient client) { }

    private final Map<String, CachedClient> clientCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.concurrent.ScheduledExecutorService CLIENT_REAPER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kubedesk-client-reaper");
                t.setDaemon(true);
                return t;
            });

    /**
     * A shared client for the context. Building a client re-parses the kubeconfig, spins up an HTTP
     * client + thread pools, does a TLS handshake and may launch an exec auth plugin — far too costly to
     * repeat per call. The returned handle ignores {@code close()} so existing try-with-resources and
     * watch/log cleanup keep working without tearing down the shared instance.
     */
    private KubernetesClient clientFor(String context) {
        File file = kubeConfigFile();
        String key = file.getAbsolutePath() + "|" + file.lastModified() + "|" + (kubeConfigOverride != null);
        CachedClient cc = clientCache.compute(context, (c, existing) -> {
            if (existing != null && existing.key().equals(key)) {
                return existing;
            }
            if (existing != null) {
                // Config changed: retire the old client after in-flight watches/logs have had time.
                KubernetesClient old = existing.client();
                CLIENT_REAPER.schedule(() -> {
                    try {
                        old.close();
                    } catch (Exception ignored) {
                        // best-effort
                    }
                }, 5, TimeUnit.MINUTES);
            }
            return new CachedClient(key, buildClient(context));
        });
        KubernetesClient real = cc.client();
        return (KubernetesClient) java.lang.reflect.Proxy.newProxyInstance(
                KubernetesClient.class.getClassLoader(), new Class<?>[]{KubernetesClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close") && method.getParameterCount() == 0) {
                        return null; // shared — never closed by callers
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** Build a client bound to a specific kube.conf context, honoring any override file. */
    private KubernetesClient buildClient(String context) {
        try {
            Config config;
            File file = kubeConfigFile();
            if (kubeConfigOverride != null && file.exists()) {
                // Build strictly from the user-chosen file so we never silently fall back.
                String contents = Files.readString(file.toPath());
                config = Config.fromKubeconfig(context, contents, file.getAbsolutePath());
            } else {
                config = Config.autoConfigure(context);
            }
            return new KubernetesClientBuilder().withConfig(config).build();
        } catch (Exception e) {
            throw new ClusterException("Could not build client for context '" + context + "': "
                    + rootMessage(e), e);
        }
    }

    /** Render an ISO-8601 creation timestamp as a compact age such as "5d", "3h" or "12m". */
    public static String age(String creationTimestamp) {
        if (creationTimestamp == null || creationTimestamp.isBlank()) {
            return "-";
        }
        try {
            Duration d = Duration.between(Instant.parse(creationTimestamp), Instant.now());
            long days = d.toDays();
            if (days > 0) {
                return days + "d";
            }
            long hours = d.toHours();
            if (hours > 0) {
                return hours + "h";
            }
            long minutes = d.toMinutes();
            if (minutes > 0) {
                return minutes + "m";
            }
            return Math.max(d.toSeconds(), 0) + "s";
        } catch (Exception e) {
            return "-";
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.toString();
    }

    /** Unchecked wrapper so the UI can show a friendly message in the status bar. */
    public static class ClusterException extends RuntimeException {
        public ClusterException(String message) {
            super(message);
        }

        public ClusterException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
