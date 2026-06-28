package com.kubedesk.service;

import com.kubedesk.service.Dtos.ContextInfo;
import com.kubedesk.service.Dtos.HealthSummary;
import com.kubedesk.service.Dtos.ResourceData;
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
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.internal.KubeConfigUtils;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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

    /** Point KubeDesk at a specific kube.conf file (or {@code null} to restore default discovery). */
    public void setKubeConfigFile(File file) {
        this.kubeConfigOverride = file;
    }

    /** The file currently in use, after applying any override. */
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
        try {
            File file = kubeConfigFile();
            if (!file.exists()) {
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
        } catch (Exception e) {
            throw new ClusterException("Could not list namespaces: " + rootMessage(e), e);
        }
    }

    @Override
    public ResourceData listResource(String context, String namespace, ResourceKind kind) {
        try (KubernetesClient client = clientFor(context)) {
            return switch (kind) {
                case PODS -> pods(client, namespace);
                case DEPLOYMENTS -> deployments(client, namespace);
                case STATEFULSETS -> statefulSets(client, namespace);
                case DAEMONSETS -> daemonSets(client, namespace);
                case JOBS -> jobs(client, namespace);
                case CRONJOBS -> cronJobs(client, namespace);
                case SERVICES -> services(client, namespace);
                case INGRESSES -> ingresses(client, namespace);
                case CONFIGMAPS -> configMaps(client, namespace);
                case SECRETS -> secrets(client, namespace);
                case PVCS -> pvcs(client, namespace);
                case NODES -> nodes(client);
            };
        } catch (Exception e) {
            throw new ClusterException("Could not list " + kind.label() + ": " + rootMessage(e), e);
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
                counts.merge(phase, 1, Integer::sum);
            }
            return new HealthSummary(counts, pods.size());
        } catch (Exception e) {
            throw new ClusterException("Could not summarise pod health: " + rootMessage(e), e);
        }
    }

    @Override
    public String describeYaml(String context, String namespace, ResourceKind kind, String name) {
        try (KubernetesClient client = clientFor(context)) {
            Object obj = switch (kind) {
                case PODS -> client.pods().inNamespace(namespace).withName(name).get();
                case DEPLOYMENTS -> client.apps().deployments().inNamespace(namespace).withName(name).get();
                case STATEFULSETS -> client.apps().statefulSets().inNamespace(namespace).withName(name).get();
                case DAEMONSETS -> client.apps().daemonSets().inNamespace(namespace).withName(name).get();
                case JOBS -> client.batch().v1().jobs().inNamespace(namespace).withName(name).get();
                case CRONJOBS -> client.batch().v1().cronjobs().inNamespace(namespace).withName(name).get();
                case SERVICES -> client.services().inNamespace(namespace).withName(name).get();
                case INGRESSES -> client.network().v1().ingresses().inNamespace(namespace).withName(name).get();
                case CONFIGMAPS -> client.configMaps().inNamespace(namespace).withName(name).get();
                case SECRETS -> client.secrets().inNamespace(namespace).withName(name).get();
                case PVCS -> client.persistentVolumeClaims().inNamespace(namespace).withName(name).get();
                case NODES -> client.nodes().withName(name).get();
            };
            if (obj == null) {
                return "# " + kind.label() + " '" + name + "' not found.";
            }
            return io.fabric8.kubernetes.client.utils.Serialization.asYaml(obj);
        } catch (Exception e) {
            throw new ClusterException("Could not load YAML for " + name + ": " + rootMessage(e), e);
        }
    }

    @Override
    public ExecSession exec(String context, String namespace, String pod, String container,
                            Consumer<String> onOutput, Runnable onClosed) {
        // Long-lived client backs the session; closed together with the ExecWatch.
        KubernetesClient client = clientFor(context);

        // An OutputStream that forwards everything the container writes to the UI callback.
        OutputStream uiOut = new OutputStream() {
            @Override
            public void write(int b) {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                onOutput.accept(new String(b, off, len, StandardCharsets.UTF_8));
            }
        };

        try {
            ExecWatch watch = client.pods().inNamespace(namespace).withName(pod)
                    .inContainer(container)
                    .redirectingInput()
                    .writingOutput(uiOut)
                    .writingError(uiOut)
                    .usingListener(new ExecListener() {
                        @Override
                        public void onFailure(Throwable t, Response failureResponse) {
                            onOutput.accept("\n[exec failed: " + t.getMessage() + "]\n");
                            onClosed.run();
                        }

                        @Override
                        public void onClose(int code, String reason) {
                            onClosed.run();
                        }
                    })
                    .exec("/bin/sh");

            OutputStream stdin = watch.getInput();
            return new ExecSession() {
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
                public void close() {
                    try {
                        watch.close();
                    } finally {
                        client.close();
                    }
                }
            };
        } catch (Exception e) {
            client.close();
            throw new ClusterException("Could not open shell in " + pod + ": " + rootMessage(e), e);
        }
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
    public String podLogs(String context, String namespace, String pod, String container, int tailLines) {
        try (KubernetesClient client = clientFor(context)) {
            var op = client.pods().inNamespace(namespace).withName(pod);
            String log = (container == null || container.isBlank())
                    ? op.tailingLines(tailLines).getLog()
                    : op.inContainer(container).tailingLines(tailLines).getLog();
            return (log == null || log.isEmpty()) ? "(no log output)" : log;
        } catch (Exception e) {
            throw new ClusterException("Could not load logs for " + pod + ": " + rootMessage(e), e);
        }
    }

    @Override
    public void deleteResource(String context, String namespace, ResourceKind kind, String name) {
        try (KubernetesClient client = clientFor(context)) {
            switch (kind) {
                case PODS -> client.pods().inNamespace(namespace).withName(name).delete();
                case DEPLOYMENTS -> client.apps().deployments().inNamespace(namespace).withName(name).delete();
                case STATEFULSETS -> client.apps().statefulSets().inNamespace(namespace).withName(name).delete();
                case DAEMONSETS -> client.apps().daemonSets().inNamespace(namespace).withName(name).delete();
                case JOBS -> client.batch().v1().jobs().inNamespace(namespace).withName(name).delete();
                case CRONJOBS -> client.batch().v1().cronjobs().inNamespace(namespace).withName(name).delete();
                case SERVICES -> client.services().inNamespace(namespace).withName(name).delete();
                case INGRESSES -> client.network().v1().ingresses().inNamespace(namespace).withName(name).delete();
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
    public void restartDeployment(String context, String namespace, String name) {
        try (KubernetesClient client = clientFor(context)) {
            client.apps().deployments().inNamespace(namespace).withName(name).rolling().restart();
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
    public void applyYaml(String context, String namespace, String yaml) {
        try (KubernetesClient client = clientFor(context)) {
            HasMetadata obj = (HasMetadata) io.fabric8.kubernetes.client.utils.Serialization.unmarshal(yaml);
            client.resource(obj).inNamespace(namespace).update();
        } catch (Exception e) {
            throw new ClusterException("Could not apply changes: " + rootMessage(e), e);
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
            case STATEFULSETS -> "StatefulSet";
            case DAEMONSETS -> "DaemonSet";
            case JOBS -> "Job";
            case CRONJOBS -> "CronJob";
            case SERVICES -> "Service";
            case INGRESSES -> "Ingress";
            case CONFIGMAPS -> "ConfigMap";
            case SECRETS -> "Secret";
            case PVCS -> "PersistentVolumeClaim";
            case NODES -> "Node";
        };
    }

    // --- per-kind table builders ------------------------------------------------------------

    private ResourceData pods(KubernetesClient client, String namespace) {
        List<String> columns = List.of("Name", "Ready", "Status", "Restarts", "Image", "Node", "Age");
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
            rows.add(List.of(
                    p.getMetadata().getName(),
                    ready + "/" + totalC,
                    podStatus(p),
                    String.valueOf(restarts),
                    images,
                    node,
                    age(p.getMetadata().getCreationTimestamp())));
        }
        return new ResourceData(columns, rows);
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
        List<String> columns = List.of("Name", "Status", "Roles", "Version");
        List<List<String>> rows = new ArrayList<>();
        for (Node n : client.nodes().list().getItems()) {
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
            String version = n.getStatus() != null && n.getStatus().getNodeInfo() != null
                    ? n.getStatus().getNodeInfo().getKubeletVersion() : "-";
            rows.add(List.of(n.getMetadata().getName(), status, roles, version));
        }
        return new ResourceData(columns, rows);
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

    // --- helpers ----------------------------------------------------------------------------

    /** Build a short-lived client bound to a specific kube.conf context, honoring any override file. */
    private KubernetesClient clientFor(String context) {
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
    static String age(String creationTimestamp) {
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
        public ClusterException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
