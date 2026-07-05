package com.kubedesk.service;

/**
 * The Kubernetes resource types KubeDesk can browse.
 *
 * <p>Each kind knows its sidebar section + label, whether it is namespaced (Nodes are cluster-scoped),
 * and its API group + resource plural (used for RBAC checks via SelfSubjectRulesReview). Enum order
 * drives the sidebar grouping.
 */
public enum ResourceKind {
    PODS("Workloads", "Pods", true, "", "pods"),
    DEPLOYMENTS("Workloads", "Deployments", true, "apps", "deployments"),
    STATEFULSETS("Workloads", "StatefulSets", true, "apps", "statefulsets"),
    DAEMONSETS("Workloads", "DaemonSets", true, "apps", "daemonsets"),
    JOBS("Workloads", "Jobs", true, "batch", "jobs"),
    CRONJOBS("Workloads", "CronJobs", true, "batch", "cronjobs"),
    SERVICES("Network", "Services", true, "", "services"),
    INGRESSES("Network", "Ingresses", true, "networking.k8s.io", "ingresses"),
    CONFIGMAPS("Config", "ConfigMaps", true, "", "configmaps"),
    SECRETS("Config", "Secrets", true, "", "secrets"),
    PVCS("Storage", "PVCs", true, "", "persistentvolumeclaims"),
    NODES("Cluster", "Nodes", false, "", "nodes");

    private final String section;
    private final String label;
    private final boolean namespaced;
    private final String apiGroup;
    private final String resource;

    ResourceKind(String section, String label, boolean namespaced, String apiGroup, String resource) {
        this.section = section;
        this.label = label;
        this.namespaced = namespaced;
        this.apiGroup = apiGroup;
        this.resource = resource;
    }

    public String section() {
        return section;
    }

    public String label() {
        return label;
    }

    public boolean namespaced() {
        return namespaced;
    }

    /** The API group (empty string for the core group), for RBAC rule matching. */
    public String apiGroup() {
        return apiGroup;
    }

    /** The resource plural (e.g. "pods"), for RBAC rule matching. */
    public String resource() {
        return resource;
    }
}
