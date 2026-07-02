package com.kubedesk.service;

import java.util.List;
import java.util.Map;

/**
 * Plain data-transfer objects passed from the {@link ClusterService} to the UI.
 *
 * <p>Keeping these free of any fabric8 types means the JavaFX layer never depends on the
 * Kubernetes client directly — the whole reason we route everything through a service interface.
 */
public final class Dtos {

    private Dtos() {
    }

    /** A single entry from the user's kube.conf. */
    public record ContextInfo(String name, String cluster, String user, String namespace, boolean current) {
    }

    /**
     * A generic, table-shaped result: the column headers plus one string list per row.
     * This lets the UI render any resource kind with a single dynamic table.
     */
    public record ResourceData(List<String> columns, List<List<String>> rows) {
    }

    /** Pod phase counts used to draw the health ring (e.g. Running=10, Pending=1, Failed=0). */
    public record HealthSummary(Map<String, Integer> phaseCounts, int total) {
    }

    /** A node in the namespace topology graph. Tier: 0=Ingress, 1=Service, 2=Workload. */
    public record TopoNode(String id, String kind, String name, int tier, String subtitle) {
    }

    /** A directed edge (from -> to) between two topology nodes, referenced by id. */
    public record TopoEdge(String from, String to) {
    }

    /** The full namespace topology: nodes plus the edges connecting them. */
    public record TopologyData(List<TopoNode> nodes, List<TopoEdge> edges) {
    }
}
