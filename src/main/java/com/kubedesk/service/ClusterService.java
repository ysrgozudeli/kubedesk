package com.kubedesk.service;

import com.kubedesk.service.Dtos.ContextInfo;
import com.kubedesk.service.Dtos.HealthFinding;
import com.kubedesk.service.Dtos.HealthSummary;
import com.kubedesk.service.Dtos.MetricSample;
import com.kubedesk.service.Dtos.Permissions;
import com.kubedesk.service.Dtos.ResourceData;
import com.kubedesk.service.Dtos.TopologyData;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * The single seam between the UI and Kubernetes.
 *
 * <p>The JavaFX layer talks only to this interface. The default implementation
 * ({@link Fabric8ClusterService}) speaks directly to the cluster API server via fabric8, but the
 * abstraction leaves room for a future {@code kubectl}-backed implementation for niche operations
 * without touching the UI.
 */
public interface ClusterService {

    /**
     * Point KubeDesk at a specific kube.conf file (or {@code null} to restore default discovery:
     * {@code $KUBECONFIG}, then {@code ~/.kube/config}). Affects subsequent calls.
     */
    void setKubeConfigFile(File file);

    /** The kube.conf file currently in use, after applying any override; {@code null} if none resolved. */
    File activeKubeConfigFile();

    /** Read all contexts defined in the active kube.conf (KUBECONFIG env or ~/.kube/config). */
    List<ContextInfo> listContexts();

    /** List namespace names visible to the given context. */
    List<String> listNamespaces(String context);

    /**
     * What the current user is allowed to do in the given namespace, via SelfSubjectRulesReview
     * (like {@code kubectl auth can-i --list}). Used to hide/disable UI you can't use. Returns
     * {@link Permissions#unknown()} (fail-open) if the review can't be performed.
     */
    Permissions permissions(String context, String namespace);

    /**
     * Fetch a resource collection as table-shaped data.
     *
     * @param namespace ignored for cluster-scoped kinds such as {@link ResourceKind#NODES}
     */
    ResourceData listResource(String context, String namespace, ResourceKind kind);

    /**
     * List a namespaced kind across several namespaces and merge into one table with a leading
     * "Namespace" column. Each namespace is queried individually (so it stays within namespace-scoped
     * RBAC), unlike a single cluster-wide list.
     */
    ResourceData listResourceAcrossNamespaces(String context, List<String> namespaces, ResourceKind kind);

    /** Summarise pod phases in a namespace for the dashboard health ring. */
    HealthSummary podHealth(String context, String namespace);

    /**
     * List all events in a namespace (the practical "namespace log"), most-recent first, as
     * table-shaped data: Type, Reason, Object, Count, Last Seen, Message.
     */
    ResourceData listEvents(String context, String namespace);

    /**
     * A current CPU/memory usage sample for a pod (summed across containers), from the Metrics API.
     * Returns {@code null} if metrics-server isn't available or the pod has no metrics yet. Poll this
     * to build a live time-series (metrics-server exposes only instantaneous usage, no history).
     */
    MetricSample podUsageSample(String context, String namespace, String pod);

    /**
     * Build a service topology for a namespace: Ingress -> Service -> Workload, derived from ingress
     * backends and label selectors.
     */
    TopologyData getTopology(String context, String namespace);

    /**
     * Scan a namespace (plus cluster Nodes) for resources failing common health checks —
     * CrashLoop/ImagePull/OOM/Evicted pods, unavailable workloads, unbound PVCs, orphaned
     * Services, unhealthy Nodes, etc. Findings are ordered most-severe first.
     */
    List<HealthFinding> healthCheck(String context, String namespace);

    /**
     * Fetch a single resource and render its full manifest as YAML (for the details panel).
     *
     * @param namespace ignored for cluster-scoped kinds such as {@link ResourceKind#NODES}
     */
    String describeYaml(String context, String namespace, ResourceKind kind, String name);

    /**
     * As {@link #describeYaml(String, String, ResourceKind, String)}, but when {@code redactSecrets}
     * is true the {@code data}/{@code stringData} values of a Secret are replaced with a placeholder
     * (keys and structure are kept). Used by the MCP server so an AI agent never receives secret
     * material; the UI keeps the unredacted view. The default implementation ignores the flag.
     */
    default String describeYaml(String context, String namespace, ResourceKind kind, String name,
                                boolean redactSecrets) {
        return describeYaml(context, namespace, kind, name);
    }

    /**
     * Fetch the events involving a resource (the "why", à la {@code kubectl describe}), formatted
     * as readable text. This is usually where the reason for a failing/red pod shows up.
     */
    String describeEvents(String context, String namespace, ResourceKind kind, String name);

    /**
     * Start a live watch on a resource collection.
     *
     * @param onChange invoked (on a background thread) on every add/modify/delete event
     * @param onClosed invoked if the watch closes abnormally, so the caller can reconnect
     * @return a handle that stops the watch (and frees its connection) when closed
     */
    AutoCloseable watchResource(String context, String namespace, ResourceKind kind,
                                Runnable onChange, Consumer<Exception> onClosed);

    /** Names of the containers in a pod (so the logs view can offer a picker for multi-container pods). */
    List<String> listPodContainers(String context, String namespace, String pod);

    /**
     * Fetch the tail of a pod container's logs.
     *
     * @param container the container name, or {@code null}/blank for the pod's default container
     * @param tailLines how many trailing lines to return
     */
    String podLogs(String context, String namespace, String pod, String container, int tailLines);

    /**
     * Stream a pod container's logs live, à la {@code kubectl logs -f}: an initial tail followed by
     * every new line as the container emits it. Used by the Logs tab's "Follow" mode.
     *
     * @param container the container name, or {@code null}/blank for the pod's default container
     * @param tailLines how many trailing lines to prime the stream with
     * @param onChunk   invoked (on a background thread) with each chunk of log text as it arrives
     * @param onClosed  invoked (on a background thread) when the stream ends — {@code null} on a clean
     *                  end (EOF), or the error that closed it
     * @return a handle that stops the stream and frees its connection when closed
     */
    AutoCloseable followLogs(String context, String namespace, String pod, String container,
                             int tailLines, Consumer<String> onChunk, Consumer<Exception> onClosed);

    // --- mutations --------------------------------------------------------------------------

    /** Delete a resource. */
    void deleteResource(String context, String namespace, ResourceKind kind, String name);

    /**
     * Delete all pods in the namespace whose {@code status.phase} is in {@code phases} (e.g. "Failed"
     * for evicted/errored pods, "Succeeded" for completed ones) — a bulk cleanup of terminal pod
     * records. Never touches Running/Pending pods. When {@code dryRun} is true, nothing is deleted and
     * the count of matching pods is returned (for a confirmation prompt); otherwise the number actually
     * deleted is returned.
     */
    int cleanupPods(String context, String namespace, java.util.Set<String> phases, boolean dryRun);

    /** Trigger a rolling restart of a Deployment (à la {@code kubectl rollout restart}). */
    void restartDeployment(String context, String namespace, String name);

    /**
     * "Restart" a single pod. Kubernetes has no restart verb for pods: the only way is to delete the
     * pod and let its controller (ReplicaSet/StatefulSet/DaemonSet/Job) create a fresh replacement.
     * This refuses to delete a <em>bare</em> pod (no controller owner), since nothing would recreate it
     * — use {@link #deleteResource} for that.
     *
     * @return the owning controller as "Kind/name", for the status message
     */
    String restartPod(String context, String namespace, String name);

    /** Scale a Deployment to the given replica count. */
    void scaleDeployment(String context, String namespace, String name, int replicas);

    /** The Deployment's current desired replica count (for pre-filling the scale dialog); null if unknown. */
    Integer getDeploymentReplicas(String context, String namespace, String name);

    /** Apply an edited manifest (YAML) back to the cluster via server-side apply. */
    void applyYaml(String context, String namespace, String yaml);

    /**
     * Create or update one or more resources from a (possibly multi-document) YAML manifest, via
     * server-side apply — like {@code kubectl apply -f}, but over the API (no kubectl).
     *
     * @param defaultNamespace namespace for documents that don't specify one
     * @return a human-readable per-resource summary of what was applied / what failed
     */
    String applyManifests(String context, String defaultNamespace, String yaml);

    /**
     * Validate manifests without changing anything, via a server-side dry-run apply. This checks the
     * documents against the cluster's real schema and admission — over the API, no kubectl.
     *
     * @return a human-readable per-resource summary of what is valid / what failed
     */
    String validateManifests(String context, String defaultNamespace, String yaml);

    /**
     * Open an interactive shell ({@code /bin/sh}) into a pod container.
     *
     * @param onOutput receives stdout/stderr text chunks (on a background thread)
     * @param onClosed invoked when the session ends
     * @return a handle to send input and close the session
     */
    ExecSession exec(String context, String namespace, String pod, String container,
                     Consumer<String> onOutput, Runnable onClosed);

    /** The container ports declared by a pod (to pre-fill the port-forward dialog). */
    List<Integer> listPodPorts(String context, String namespace, String pod);

    /**
     * Start a port-forward from a local port to a pod port, over the API (no kubectl).
     *
     * @param localPort the local port to bind, or {@code <= 0} to auto-pick a free one
     */
    PortForward startPortForward(String context, String namespace, String pod,
                                 int remotePort, int localPort);
}
