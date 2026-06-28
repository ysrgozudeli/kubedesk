package com.kubedesk.service;

import com.kubedesk.service.Dtos.ContextInfo;
import com.kubedesk.service.Dtos.HealthSummary;
import com.kubedesk.service.Dtos.ResourceData;

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

    /** Read all contexts defined in the active kube.conf (KUBECONFIG env or ~/.kube/config). */
    List<ContextInfo> listContexts();

    /** List namespace names visible to the given context. */
    List<String> listNamespaces(String context);

    /**
     * Fetch a resource collection as table-shaped data.
     *
     * @param namespace ignored for cluster-scoped kinds such as {@link ResourceKind#NODES}
     */
    ResourceData listResource(String context, String namespace, ResourceKind kind);

    /** Summarise pod phases in a namespace for the dashboard health ring. */
    HealthSummary podHealth(String context, String namespace);

    /**
     * Fetch a single resource and render its full manifest as YAML (for the details panel).
     *
     * @param namespace ignored for cluster-scoped kinds such as {@link ResourceKind#NODES}
     */
    String describeYaml(String context, String namespace, ResourceKind kind, String name);

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

    // --- mutations --------------------------------------------------------------------------

    /** Delete a resource. */
    void deleteResource(String context, String namespace, ResourceKind kind, String name);

    /** Trigger a rolling restart of a Deployment (à la {@code kubectl rollout restart}). */
    void restartDeployment(String context, String namespace, String name);

    /** Scale a Deployment to the given replica count. */
    void scaleDeployment(String context, String namespace, String name, int replicas);

    /** Apply an edited manifest (YAML) back to the cluster, replacing the existing resource. */
    void applyYaml(String context, String namespace, String yaml);

    /**
     * Open an interactive shell ({@code /bin/sh}) into a pod container.
     *
     * @param onOutput receives stdout/stderr text chunks (on a background thread)
     * @param onClosed invoked when the session ends
     * @return a handle to send input and close the session
     */
    ExecSession exec(String context, String namespace, String pod, String container,
                     Consumer<String> onOutput, Runnable onClosed);
}
