package com.kubedesk.service.crd;

import com.kubedesk.service.Dtos.CrdCondition;
import com.kubedesk.service.Dtos.CrdDetails;
import com.kubedesk.service.Dtos.CrdRelation;
import com.kubedesk.service.Fabric8ClusterService;
import com.kubedesk.service.ResourceKind;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * External Secrets Operator ({@code external-secrets.io}) as a read-only CRD module: ExternalSecrets
 * (what to pull and which Secret it becomes), SecretStores and ClusterSecretStores (the backends).
 * The Status tab shows sync health, the store it uses and the Secret it produces. Secret values are
 * never read; only key names and the target Secret's key count.
 */
public final class ExternalSecretsApi {
    private ExternalSecretsApi() {
    }

    public static final String GROUP = "external-secrets.io";
    private static final List<String> V = List.of("v1", "v1beta1");

    public static final CrdKind EXTERNAL_SECRET = new CrdKind(GROUP, "ExternalSecret", "externalsecrets", true, V,
            List.of("Name", "Ready", "Store", "Target", "Refresh", "Last sync", "Age"),
            r -> List.of(name(r), ready(r), storeRef(r), targetName(r), refreshInterval(r),
                    Fabric8ClusterService.age(Json.str(r, "status", "refreshTime")), age(r)),
            ExternalSecretsApi::inspectExternalSecret);

    public static final CrdKind SECRET_STORE = new CrdKind(GROUP, "SecretStore", "secretstores", true, V,
            List.of("Name", "Ready", "Provider", "Age"),
            r -> List.of(name(r), ready(r), provider(r), age(r)),
            ExternalSecretsApi::inspectStore);

    public static final CrdKind CLUSTER_SECRET_STORE = new CrdKind(GROUP, "ClusterSecretStore",
            "clustersecretstores", false, V,
            List.of("Name", "Ready", "Provider", "Age"),
            r -> List.of(name(r), ready(r), provider(r), age(r)),
            ExternalSecretsApi::inspectStore);

    public static Map<ResourceKind, CrdKind> kinds() {
        Map<ResourceKind, CrdKind> m = new EnumMap<>(ResourceKind.class);
        m.put(ResourceKind.EXTERNALSECRETS, EXTERNAL_SECRET);
        m.put(ResourceKind.SECRETSTORES, SECRET_STORE);
        m.put(ResourceKind.CLUSTERSECRETSTORES, CLUSTER_SECRET_STORE);
        return m;
    }

    // ---- row helpers ----

    private static String name(GenericKubernetesResource r) {
        return r.getMetadata().getName();
    }

    private static String age(GenericKubernetesResource r) {
        return Fabric8ClusterService.age(r.getMetadata().getCreationTimestamp());
    }

    private static String ready(GenericKubernetesResource r) {
        return Json.condition(Json.maps(r, "status", "conditions"), "Ready");
    }

    /** "name" for a SecretStore (the default), "ClusterSecretStore/name" otherwise. */
    private static String storeRef(GenericKubernetesResource r) {
        String n = Json.str(r, "spec", "secretStoreRef", "name");
        if (n.isBlank()) {
            return "";
        }
        String k = Json.str(r, "spec", "secretStoreRef", "kind");
        return (k.isBlank() || "SecretStore".equals(k)) ? n : k + "/" + n;
    }

    /** The Secret the operator creates: spec.target.name, defaulting to the ExternalSecret's own name. */
    private static String targetName(GenericKubernetesResource r) {
        String t = Json.str(r, "spec", "target", "name");
        return t.isBlank() ? r.getMetadata().getName() : t;
    }

    private static String refreshInterval(GenericKubernetesResource r) {
        String i = Json.str(r, "spec", "refreshInterval");
        return i.isBlank() ? "1h" : i;
    }

    /** The configured backend under spec.provider (aws, vault, gcpsm, fake, …). */
    private static String provider(GenericKubernetesResource r) {
        Object p = Json.at(r, "spec", "provider");
        if (p instanceof Map<?, ?> m && !m.isEmpty()) {
            return String.valueOf(m.keySet().iterator().next());
        }
        return "";
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

    private static CrdDetails inspectExternalSecret(KubernetesClient client, GenericKubernetesResource r) {
        List<CrdRelation> rel = new ArrayList<>();
        String ns = r.getMetadata().getNamespace();

        String store = Json.str(r, "spec", "secretStoreRef", "name");
        if (!store.isBlank()) {
            boolean cluster = "ClusterSecretStore".equals(Json.str(r, "spec", "secretStoreRef", "kind"));
            rel.add(new CrdRelation("Reads from", cluster ? "ClusterSecretStore" : "SecretStore",
                    cluster ? "" : ns, store, ""));
        }

        String target = targetName(r);
        String info;
        try {
            var s = client.secrets().inNamespace(ns).withName(target).get();
            info = s == null ? "not created yet" : (s.getData() == null ? 0 : s.getData().size()) + " key(s)";
        } catch (Exception e) {
            info = "";
        }
        rel.add(new CrdRelation("Creates this Secret", "Secret", ns, target, info));

        String refreshTime = Json.str(r, "status", "refreshTime");
        if (!refreshTime.isBlank()) {
            rel.add(new CrdRelation("Last refresh", "", "", refreshTime, ""));
        }
        for (Map<String, Object> d : Json.maps(r, "spec", "data")) {
            String remote = Json.str(Json.walk(d, "remoteRef", "key"));
            String prop = Json.str(Json.walk(d, "remoteRef", "property"));
            rel.add(new CrdRelation("Key mapping", "", "", Json.str(d.get("secretKey")),
                    "from " + remote + (prop.isBlank() ? "" : " [" + prop + "]")));
        }
        for (Map<String, Object> d : Json.maps(r, "spec", "dataFrom")) {
            String remote = Json.str(Json.walk(d, "extract", "key"));
            if (remote.isBlank()) {
                remote = Json.str(Json.walk(d, "find", "name", "regexp"));
            }
            rel.add(new CrdRelation("Bulk import (dataFrom)", "", "", remote, ""));
        }
        return new CrdDetails(conditions(r), rel);
    }

    private static CrdDetails inspectStore(KubernetesClient client, GenericKubernetesResource r) {
        List<CrdRelation> rel = new ArrayList<>();
        String p = provider(r);
        if (!p.isBlank()) {
            rel.add(new CrdRelation("Provider", "", "", p, ""));
        }
        String refresh = Json.str(r, "spec", "refreshInterval");
        if (!refresh.isBlank()) {
            rel.add(new CrdRelation("Refresh interval", "", "", refresh, ""));
        }
        return new CrdDetails(conditions(r), rel);
    }
}
