package com.kubedesk.service.crd;

import com.kubedesk.service.Dtos.CrdDetails;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.List;
import java.util.function.Function;

/**
 * Describes one custom-resource kind KubeDesk can browse read-only: where it lives in the API, which
 * versions to try (newest first), and how to turn an object into a table row. A "module" (Gateway API,
 * and later cert-manager, External Secrets, …) is just a set of these plus any extras it needs.
 *
 * @param group      API group, e.g. {@code gateway.networking.k8s.io}
 * @param kind       the {@code kind} string, e.g. {@code HTTPRoute} (also used to match event objects)
 * @param plural     the resource plural, e.g. {@code httproutes}
 * @param namespaced false for cluster-scoped kinds (GatewayClass)
 * @param versions   API versions to try, in preference order; the first one the cluster serves is used
 * @param columns    table column headers; the first must be the name
 * @param row        maps one object to its table row (same length as {@code columns})
 * @param inspector  builds the Status view (conditions + relations); may be null for a plain kind
 */
public record CrdKind(String group, String kind, String plural, boolean namespaced,
                      List<String> versions, List<String> columns,
                      Function<GenericKubernetesResource, List<String>> row,
                      Inspector inspector) {

    /** Builds the {@link CrdDetails} for one object; may query the cluster for related objects. */
    @FunctionalInterface
    public interface Inspector {
        CrdDetails inspect(KubernetesClient client, GenericKubernetesResource obj);
    }
}
