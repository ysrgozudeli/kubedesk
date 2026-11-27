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
    REPLICASETS("Workloads", "ReplicaSets", true, "apps", "replicasets"),
    STATEFULSETS("Workloads", "StatefulSets", true, "apps", "statefulsets"),
    DAEMONSETS("Workloads", "DaemonSets", true, "apps", "daemonsets"),
    JOBS("Workloads", "Jobs", true, "batch", "jobs"),
    CRONJOBS("Workloads", "CronJobs", true, "batch", "cronjobs"),
    SERVICES("Network", "Services", true, "", "services"),
    INGRESSES("Network", "Ingresses", true, "networking.k8s.io", "ingresses"),
    // Gateway API (read-only, CRD-backed — shown only when the cluster has the CRDs; see service.crd.GatewayApi)
    GATEWAYCLASSES("Network", "GatewayClasses", false, "gateway.networking.k8s.io", "gatewayclasses", true),
    GATEWAYS("Network", "Gateways", true, "gateway.networking.k8s.io", "gateways", true),
    HTTPROUTES("Network", "HTTPRoutes", true, "gateway.networking.k8s.io", "httproutes", true),
    GRPCROUTES("Network", "GRPCRoutes", true, "gateway.networking.k8s.io", "grpcroutes", true),
    CONFIGMAPS("Config", "ConfigMaps", true, "", "configmaps"),
    SECRETS("Config", "Secrets", true, "", "secrets"),
    // Sealed Secrets (read-only, CRD-backed — shown only when the controller's CRD exists; see service.crd.SealedSecretsApi)
    SEALEDSECRETS("Config", "SealedSecrets", true, "bitnami.com", "sealedsecrets", true),
    // cert-manager (read-only, CRD-backed; see service.crd.CertManager)
    CERTIFICATES("Config", "Certificates", true, "cert-manager.io", "certificates", true),
    CERTIFICATEREQUESTS("Config", "CertRequests", true, "cert-manager.io", "certificaterequests", true),
    ISSUERS("Config", "Issuers", true, "cert-manager.io", "issuers", true),
    CLUSTERISSUERS("Config", "ClusterIssuers", false, "cert-manager.io", "clusterissuers", true),
    // External Secrets Operator (read-only, CRD-backed; see service.crd.ExternalSecretsApi)
    EXTERNALSECRETS("Config", "ExternalSecrets", true, "external-secrets.io", "externalsecrets", true),
    SECRETSTORES("Config", "SecretStores", true, "external-secrets.io", "secretstores", true),
    CLUSTERSECRETSTORES("Config", "ClusterSecretStores", false, "external-secrets.io", "clustersecretstores", true),
    PVCS("Storage", "PVCs", true, "", "persistentvolumeclaims"),
    NODES("Cluster", "Nodes", false, "", "nodes");

    private final String section;
    private final String label;
    private final boolean namespaced;
    private final String apiGroup;
    private final String resource;
    private final boolean crdBacked;

    ResourceKind(String section, String label, boolean namespaced, String apiGroup, String resource) {
        this(section, label, namespaced, apiGroup, resource, false);
    }

    ResourceKind(String section, String label, boolean namespaced, String apiGroup, String resource,
                 boolean crdBacked) {
        this.section = section;
        this.label = label;
        this.namespaced = namespaced;
        this.apiGroup = apiGroup;
        this.resource = resource;
        this.crdBacked = crdBacked;
    }

    /**
     * True for kinds backed by a custom resource definition (read-only, shown only when the cluster
     * serves the group). Their listing/YAML/watch go through {@code service.crd}, not typed clients.
     */
    public boolean crdBacked() {
        return crdBacked;
    }

    /** The CRD descriptor for a {@link #crdBacked()} kind; null otherwise. */
    public com.kubedesk.service.crd.CrdKind crd() {
        return crdBacked ? com.kubedesk.service.crd.CrdModules.forKind(this) : null;
    }

    /** The CRD kind whose {@code kind} string (e.g. "HTTPRoute") matches, or null. For event / topology links. */
    public static ResourceKind forCrdKindName(String kindName) {
        return com.kubedesk.service.crd.CrdModules.byKindName(kindName);
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
