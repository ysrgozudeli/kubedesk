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
 * Bitnami Sealed Secrets ({@code bitnami.com}) as a read-only CRD module: lists the {@code SealedSecret}
 * objects (the encrypted, Git-safe form) and, in the Status tab, whether the controller decrypted them
 * (the {@code Synced} condition) and which Secret they produce. Encrypted values are never shown as
 * anything but the key names.
 */
public final class SealedSecretsApi {
    private SealedSecretsApi() {
    }

    public static final String GROUP = "bitnami.com";

    private static final String SCOPE_CLUSTER = "sealedsecrets.bitnami.com/cluster-wide";
    private static final String SCOPE_NAMESPACE = "sealedsecrets.bitnami.com/namespace-wide";

    public static final CrdKind SEALED_SECRET = new CrdKind(GROUP, "SealedSecret", "sealedsecrets", true,
            List.of("v1alpha1"),
            List.of("Name", "Type", "Keys", "Scope", "Synced", "Age"),
            r -> List.of(r.getMetadata().getName(), type(r), String.valueOf(keys(r).size()), scope(r),
                    synced(r), Fabric8ClusterService.age(r.getMetadata().getCreationTimestamp())),
            SealedSecretsApi::inspect);

    public static Map<ResourceKind, CrdKind> kinds() {
        Map<ResourceKind, CrdKind> m = new EnumMap<>(ResourceKind.class);
        m.put(ResourceKind.SEALEDSECRETS, SEALED_SECRET);
        return m;
    }

    private static String type(GenericKubernetesResource r) {
        String t = Json.str(r, "spec", "template", "type");
        return t.isBlank() ? "Opaque" : t;
    }

    /** Names of the encrypted keys (the values themselves are opaque ciphertext). */
    private static List<String> keys(GenericKubernetesResource r) {
        Object enc = Json.at(r, "spec", "encryptedData");
        List<String> out = new ArrayList<>();
        if (enc instanceof Map<?, ?> m) {
            m.keySet().forEach(k -> out.add(String.valueOf(k)));
        }
        return out;
    }

    /** Strict (default) binds the seal to name + namespace; the annotations widen it. */
    private static String scope(GenericKubernetesResource r) {
        Map<String, String> ann = r.getMetadata().getAnnotations();
        if (ann != null && "true".equals(ann.get(SCOPE_CLUSTER))) {
            return "cluster-wide";
        }
        if (ann != null && "true".equals(ann.get(SCOPE_NAMESPACE))) {
            return "namespace-wide";
        }
        return "strict";
    }

    private static String synced(GenericKubernetesResource r) {
        return Json.condition(Json.maps(r, "status", "conditions"), "Synced");
    }

    private static CrdDetails inspect(KubernetesClient client, GenericKubernetesResource r) {
        List<CrdCondition> conditions = new ArrayList<>();
        for (Map<String, Object> c : Json.maps(r, "status", "conditions")) {
            conditions.add(new CrdCondition("", Json.str(c.get("type")), Json.str(c.get("status")),
                    Json.str(c.get("reason")), Json.str(c.get("message")),
                    Json.str(c.get("lastTransitionTime"))));
        }

        List<CrdRelation> rel = new ArrayList<>();
        String ns = r.getMetadata().getNamespace();
        String target = Json.str(r, "spec", "template", "metadata", "name");
        if (target.isBlank()) {
            target = r.getMetadata().getName();
        }
        String info;
        try {
            var secret = client.secrets().inNamespace(ns).withName(target).get();
            info = secret == null ? "not created yet"
                    : (secret.getData() == null ? 0 : secret.getData().size()) + " key(s)";
        } catch (Exception e) {
            info = "";
        }
        rel.add(new CrdRelation("Creates this Secret", "Secret", ns, target, info));
        for (String k : keys(r)) {
            rel.add(new CrdRelation("Encrypted keys", "", "", k, "encrypted"));
        }
        return new CrdDetails(conditions, rel);
    }
}
