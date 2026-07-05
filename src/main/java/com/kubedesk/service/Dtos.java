package com.kubedesk.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** One RBAC resource rule from a SelfSubjectRulesReview (verbs × apiGroups × resources). */
    public record ResourceRuleDto(Set<String> verbs, Set<String> apiGroups, Set<String> resources) {
    }

    /**
     * What the current user is allowed to do in a namespace (from SelfSubjectRulesReview). Designed to
     * FAIL OPEN: when the rules are unknown or incomplete, {@link #can} returns true, so we never hide
     * something the user can actually access due to a review quirk.
     */
    public record Permissions(List<ResourceRuleDto> rules, boolean complete, boolean known) {
        /** Use when permissions can't be determined — treat everything as allowed. */
        public static Permissions unknown() {
            return new Permissions(List.of(), false, false);
        }

        public boolean can(String apiGroup, String resource, String verb) {
            if (!known || !complete) {
                return true; // fail open
            }
            for (ResourceRuleDto r : rules) {
                if (matches(r.verbs(), verb) && matches(r.apiGroups(), apiGroup)
                        && matches(r.resources(), resource)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean matches(Set<String> set, String value) {
            return set != null && (set.contains("*") || set.contains(value));
        }
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

    /** Severity of a health finding. */
    public enum HealthSeverity {
        CRITICAL, WARNING
    }

    /** A single health-scan finding: a resource failing a common check. */
    public record HealthFinding(HealthSeverity severity, ResourceKind kind, String name,
                                String check, String detail) {
    }
}
