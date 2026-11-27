package com.kubedesk.service.crd;

import com.kubedesk.service.Dtos.ResourceData;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Generic, read-only access to custom resources described by a {@link CrdKind}: version discovery,
 * list / get / watch through fabric8's untyped client, and group-availability checks. Everything
 * CRD-specific (columns, parsing) lives in the module that declares the kinds, not here.
 */
public final class CrdSupport {
    private CrdSupport() {
    }

    /** Resolved version per (server, group, plural) so we don't hit API discovery on every refresh. */
    private static final Map<String, String> VERSION_CACHE = new ConcurrentHashMap<>();

    /** True when the cluster serves {@code group} at all (i.e. the CRDs are installed). */
    public static boolean groupAvailable(KubernetesClient client, String group) {
        try {
            return client.getApiGroup(group) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** The first of {@code kind.versions()} the cluster serves for this resource; falls back to the first listed. */
    static String resolveVersion(KubernetesClient client, CrdKind kind) {
        String key = client.getMasterUrl() + "|" + kind.group() + "|" + kind.plural();
        String cached = VERSION_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String chosen = null;
        for (String v : kind.versions()) {
            try {
                var list = client.getApiResources(kind.group() + "/" + v);
                if (list != null && list.getResources() != null
                        && list.getResources().stream().anyMatch(r -> kind.plural().equals(r.getName()))) {
                    chosen = v;
                    break;
                }
            } catch (Exception ignored) {
                // that version isn't served; try the next
            }
        }
        if (chosen == null) {
            return kind.versions().get(0); // don't cache a miss: the CRD may be installed later
        }
        VERSION_CACHE.put(key, chosen);
        return chosen;
    }

    private static MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>> op(KubernetesClient client, CrdKind kind) {
        ResourceDefinitionContext ctx = new ResourceDefinitionContext.Builder()
                .withGroup(kind.group())
                .withVersion(resolveVersion(client, kind))
                .withKind(kind.kind())
                .withPlural(kind.plural())
                .withNamespaced(kind.namespaced())
                .build();
        return client.genericKubernetesResources(ctx);
    }

    public static List<GenericKubernetesResource> items(KubernetesClient client, CrdKind kind, String namespace) {
        var op = op(client, kind);
        var list = (kind.namespaced() && namespace != null && !namespace.isBlank())
                ? op.inNamespace(namespace).list()
                : op.list();
        return list.getItems() != null ? list.getItems() : List.of();
    }

    public static ResourceData list(KubernetesClient client, CrdKind kind, String namespace) {
        List<List<String>> rows = new ArrayList<>();
        for (GenericKubernetesResource r : items(client, kind, namespace)) {
            rows.add(kind.row().apply(r));
        }
        return new ResourceData(kind.columns(), rows);
    }

    public static GenericKubernetesResource get(KubernetesClient client, CrdKind kind, String namespace, String name) {
        var op = op(client, kind);
        return kind.namespaced() ? op.inNamespace(namespace).withName(name).get() : op.withName(name).get();
    }

    /**
     * Whether the object exists. Any error (RBAC, API hiccup) counts as "exists", so we never claim an
     * object is missing when we simply couldn't check.
     */
    public static boolean exists(KubernetesClient client, CrdKind kind, String namespace, String name) {
        try {
            return get(client, kind, namespace, name) != null;
        } catch (Exception e) {
            return true;
        }
    }

    public static Watch watch(KubernetesClient client, CrdKind kind, String namespace,
                              Watcher<GenericKubernetesResource> watcher) {
        var op = op(client, kind);
        return kind.namespaced() ? op.inNamespace(namespace).watch(watcher) : op.watch(watcher);
    }
}
