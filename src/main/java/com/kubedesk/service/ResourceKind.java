package com.kubedesk.service;

/**
 * The Kubernetes resource types KubeDesk can browse.
 *
 * <p>Each kind knows its sidebar section + label and whether it is namespaced
 * (Nodes are cluster-scoped). Enum order drives the sidebar grouping.
 */
public enum ResourceKind {
    PODS("Workloads", "Pods", true),
    DEPLOYMENTS("Workloads", "Deployments", true),
    STATEFULSETS("Workloads", "StatefulSets", true),
    DAEMONSETS("Workloads", "DaemonSets", true),
    JOBS("Workloads", "Jobs", true),
    CRONJOBS("Workloads", "CronJobs", true),
    SERVICES("Network", "Services", true),
    INGRESSES("Network", "Ingresses", true),
    CONFIGMAPS("Config", "ConfigMaps", true),
    SECRETS("Config", "Secrets", true),
    PVCS("Storage", "PVCs", true),
    NODES("Cluster", "Nodes", false);

    private final String section;
    private final String label;
    private final boolean namespaced;

    ResourceKind(String section, String label, boolean namespaced) {
        this.section = section;
        this.label = label;
        this.namespaced = namespaced;
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
}
