package com.kubedesk.service.crd;

import com.kubedesk.service.Dtos;
import com.kubedesk.service.Dtos.CrdCondition;
import com.kubedesk.service.Dtos.CrdDetails;
import com.kubedesk.service.Dtos.CrdRelation;
import com.kubedesk.service.Fabric8ClusterService;
import com.kubedesk.service.ResourceKind;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * cert-manager ({@code cert-manager.io}) as a read-only CRD module: Certificates, Issuers,
 * ClusterIssuers and CertificateRequests. The Status tab shows the Ready condition plus the chain
 * Certificate → Issuer → Secret, and expiry / renewal times.
 */
public final class CertManager {
    private CertManager() {
    }

    public static final String GROUP = "cert-manager.io";
    private static final List<String> V = List.of("v1");

    public static final CrdKind CERTIFICATE = new CrdKind(GROUP, "Certificate", "certificates", true, V,
            List.of("Name", "Ready", "Secret", "Issuer", "Expires", "Age"),
            r -> List.of(name(r), ready(r), Json.str(r, "spec", "secretName"), issuerRef(r),
                    expires(r), age(r)),
            CertManager::inspectCertificate);

    public static final CrdKind ISSUER = new CrdKind(GROUP, "Issuer", "issuers", true, V,
            List.of("Name", "Ready", "Type", "Age"),
            r -> List.of(name(r), ready(r), issuerType(r), age(r)),
            CertManager::inspectIssuer);

    public static final CrdKind CLUSTER_ISSUER = new CrdKind(GROUP, "ClusterIssuer", "clusterissuers", false, V,
            List.of("Name", "Ready", "Type", "Age"),
            r -> List.of(name(r), ready(r), issuerType(r), age(r)),
            CertManager::inspectIssuer);

    public static final CrdKind CERTIFICATE_REQUEST = new CrdKind(GROUP, "CertificateRequest",
            "certificaterequests", true, V,
            List.of("Name", "Approved", "Ready", "Issuer", "Age"),
            r -> List.of(name(r), cond(r, "Approved"), ready(r), issuerRef(r), age(r)),
            CertManager::inspectRequest);

    public static Map<ResourceKind, CrdKind> kinds() {
        Map<ResourceKind, CrdKind> m = new EnumMap<>(ResourceKind.class);
        m.put(ResourceKind.CERTIFICATES, CERTIFICATE);
        m.put(ResourceKind.CERTIFICATEREQUESTS, CERTIFICATE_REQUEST);
        m.put(ResourceKind.ISSUERS, ISSUER);
        m.put(ResourceKind.CLUSTERISSUERS, CLUSTER_ISSUER);
        return m;
    }

    // ---- row helpers ----

    private static String name(GenericKubernetesResource r) {
        return r.getMetadata().getName();
    }

    private static String age(GenericKubernetesResource r) {
        return Fabric8ClusterService.age(r.getMetadata().getCreationTimestamp());
    }

    private static String cond(GenericKubernetesResource r, String type) {
        return Json.condition(Json.maps(r, "status", "conditions"), type);
    }

    private static String ready(GenericKubernetesResource r) {
        return cond(r, "Ready");
    }

    private static String issuerRef(GenericKubernetesResource r) {
        String n = Json.str(r, "spec", "issuerRef", "name");
        if (n.isBlank()) {
            return "";
        }
        String k = Json.str(r, "spec", "issuerRef", "kind");
        return (k.isBlank() || "Issuer".equals(k)) ? n : k + "/" + n;
    }

    /** First configured issuer backend under spec (acme, ca, selfSigned, vault, venafi…). */
    private static String issuerType(GenericKubernetesResource r) {
        Object spec = Json.at(r, "spec");
        if (spec instanceof Map<?, ?> m && !m.isEmpty()) {
            return String.valueOf(m.keySet().iterator().next());
        }
        return "";
    }

    /** "12d" until notAfter, "expired", or "" when not yet issued. */
    private static String expires(GenericKubernetesResource r) {
        String notAfter = Json.str(r, "status", "notAfter");
        if (notAfter.isBlank()) {
            return "";
        }
        try {
            Duration left = Duration.between(Instant.now(), Instant.parse(notAfter));
            if (left.isNegative()) {
                return "expired";
            }
            long days = left.toDays();
            return days > 0 ? days + "d" : left.toHours() + "h";
        } catch (Exception e) {
            return notAfter;
        }
    }

    private static List<CrdCondition> conditions(GenericKubernetesResource r) {
        List<CrdCondition> out = new ArrayList<>();
        for (Map<String, Object> c : Json.maps(r, "status", "conditions")) {
            out.add(new CrdCondition("", Json.str(c.get("type")), Json.str(c.get("status")),
                    Json.str(c.get("reason")), Json.str(c.get("message")),
                    Json.str(c.get("lastTransitionTime"))));
        }
        return out;
    }

    // ---- inspectors ----

    private static CrdDetails inspectCertificate(KubernetesClient client, GenericKubernetesResource r) {
        List<CrdRelation> rel = new ArrayList<>();
        String ns = r.getMetadata().getNamespace();

        CrdRelation issuedBy = issuerRelation(client, r);
        if (issuedBy != null) {
            rel.add(issuedBy);
        }

        String secretName = Json.str(r, "spec", "secretName");
        if (!secretName.isBlank()) {
            String info;
            try {
                var s = client.secrets().inNamespace(ns).withName(secretName).get();
                info = s == null ? "not created yet"
                        : (s.getData() == null ? 0 : s.getData().size()) + " key(s)";
            } catch (Exception e) {
                info = "";
            }
            rel.add(new CrdRelation("Stores key pair in Secret", "Secret", ns, secretName, info));
        }

        addInfo(rel, "Not before", Json.str(r, "status", "notBefore"));
        addInfo(rel, "Expires", Json.str(r, "status", "notAfter"));
        addInfo(rel, "Renews at", Json.str(r, "status", "renewalTime"));
        addInfo(rel, "Revision", Json.str(r, "status", "revision"));
        for (String dns : Json.strings(Json.at(r, "spec", "dnsNames"))) {
            addInfo(rel, "DNS name", dns);
        }
        return new CrdDetails(conditions(r), rel);
    }

    private static CrdDetails inspectIssuer(KubernetesClient client, GenericKubernetesResource r) {
        List<CrdRelation> rel = new ArrayList<>();
        addInfo(rel, "Type", issuerType(r));
        String server = Json.str(r, "spec", "acme", "server");
        addInfo(rel, "ACME server", server);
        String ca = Json.str(r, "spec", "ca", "secretName");
        if (!ca.isBlank()) {
            rel.add(new CrdRelation("CA key pair in Secret", "Secret",
                    r.getMetadata().getNamespace() == null ? "" : r.getMetadata().getNamespace(), ca, ""));
        }
        return new CrdDetails(conditions(r), rel);
    }

    private static CrdDetails inspectRequest(KubernetesClient client, GenericKubernetesResource r) {
        List<CrdRelation> rel = new ArrayList<>();
        String ns = r.getMetadata().getNamespace();
        CrdRelation issuedBy = issuerRelation(client, r);
        if (issuedBy != null) {
            rel.add(issuedBy);
        }
        if (r.getMetadata().getOwnerReferences() != null) {
            r.getMetadata().getOwnerReferences().stream()
                    .filter(o -> "Certificate".equals(o.getKind())).findFirst()
                    .ifPresent(o -> rel.add(new CrdRelation("Requested for", "Certificate", ns, o.getName(), "")));
        }
        return new CrdDetails(conditions(r), rel);
    }

    /** Relation to the Issuer/ClusterIssuer a resource points at, flagged when that issuer doesn't exist. */
    private static CrdRelation issuerRelation(KubernetesClient client, GenericKubernetesResource r) {
        String name = Json.str(r, "spec", "issuerRef", "name");
        if (name.isBlank()) {
            return null;
        }
        String ns = r.getMetadata().getNamespace();
        boolean cluster = "ClusterIssuer".equals(Json.str(r, "spec", "issuerRef", "kind"));
        boolean found = CrdSupport.exists(client, cluster ? CLUSTER_ISSUER : ISSUER, cluster ? "" : ns, name);
        return new CrdRelation("Issued by", cluster ? "ClusterIssuer" : "Issuer", cluster ? "" : ns, name,
                found ? "" : "not found");
    }

    /**
     * Health-scan findings for cert-manager objects in {@code namespace} (plus ClusterIssuers): certificates
     * that aren't Ready or expire within 14 days, and issuers that aren't Ready.
     */
    public static List<Dtos.HealthFinding> healthFindings(KubernetesClient client, String namespace) {
        List<Dtos.HealthFinding> out = new ArrayList<>();
        if (!CrdSupport.groupAvailable(client, GROUP)) {
            return out;
        }
        try {
            for (var c : CrdSupport.items(client, CERTIFICATE, namespace)) {
                String n = name(c);
                var conds = Json.maps(c, "status", "conditions");
                if ("False".equals(ready(c))) {
                    boolean issuing = "True".equals(Json.condition(conds, "Issuing"));
                    CrdRelation issuedBy = issuerRelation(client, c);
                    boolean issuerMissing = issuedBy != null && "not found".equals(issuedBy.info());
                    String detail = reasonMessage(conds, "Ready");
                    if (issuerMissing) {
                        detail = issuedBy.kind() + " " + issuedBy.name() + " not found. " + detail;
                    }
                    boolean transientState = issuing && !issuerMissing;
                    out.add(new Dtos.HealthFinding(
                            transientState ? Dtos.HealthSeverity.WARNING : Dtos.HealthSeverity.CRITICAL,
                            ResourceKind.CERTIFICATES, n, transientState ? "Issuing" : "Not Ready", detail));
                    continue;
                }
                String notAfter = Json.str(c, "status", "notAfter");
                if (!notAfter.isBlank()) {
                    try {
                        Duration left = Duration.between(Instant.now(), Instant.parse(notAfter));
                        if (left.isNegative()) {
                            out.add(new Dtos.HealthFinding(Dtos.HealthSeverity.CRITICAL, ResourceKind.CERTIFICATES,
                                    n, "Expired", "Certificate expired on " + notAfter));
                        } else if (left.toDays() < 14) {
                            out.add(new Dtos.HealthFinding(Dtos.HealthSeverity.WARNING, ResourceKind.CERTIFICATES,
                                    n, "Expiring soon", "Expires in " + expires(c) + " (" + notAfter + ")"));
                        }
                    } catch (Exception ignored) {
                        // unparseable timestamp: skip the expiry check
                    }
                }
            }
            for (var i : CrdSupport.items(client, ISSUER, namespace)) {
                issuerFinding(i, ResourceKind.ISSUERS, out);
            }
            for (var i : CrdSupport.items(client, CLUSTER_ISSUER, "")) {
                issuerFinding(i, ResourceKind.CLUSTERISSUERS, out);
            }
        } catch (Exception ignored) {
            // a cert-manager hiccup must not break the rest of the scan
        }
        return out;
    }

    private static void issuerFinding(GenericKubernetesResource i, ResourceKind kind, List<Dtos.HealthFinding> out) {
        if ("False".equals(ready(i))) {
            out.add(new Dtos.HealthFinding(Dtos.HealthSeverity.CRITICAL, kind, name(i), "Not Ready",
                    reasonMessage(Json.maps(i, "status", "conditions"), "Ready")));
        }
    }

    private static String reasonMessage(List<Map<String, Object>> conds, String type) {
        for (Map<String, Object> c : conds) {
            if (type.equals(c.get("type"))) {
                String reason = Json.str(c.get("reason"));
                String msg = Json.str(c.get("message"));
                return reason.isBlank() ? msg : msg.isBlank() ? reason : reason + ": " + msg;
            }
        }
        return "";
    }

    private static void addInfo(List<CrdRelation> rel, String label, String value) {
        if (value != null && !value.isBlank()) {
            rel.add(new CrdRelation(label, "", "", value, ""));
        }
    }
}
