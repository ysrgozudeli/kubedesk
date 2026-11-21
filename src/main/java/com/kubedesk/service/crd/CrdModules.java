package com.kubedesk.service.crd;

import com.kubedesk.service.ResourceKind;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Registry of the CRD modules KubeDesk knows. To add a family (cert-manager, External Secrets, …):
 * write a class like {@link GatewayApi}, add its {@link ResourceKind} constants, and register its
 * {@code kinds()} map here — nothing else in the service or UI needs a new switch arm.
 */
public final class CrdModules {
    private CrdModules() {
    }

    private static final class Holder {
        static final Map<ResourceKind, CrdKind> ALL = new java.util.EnumMap<>(ResourceKind.class);

        static {
            ALL.putAll(GatewayApi.kinds());
            // future modules: ALL.putAll(CertManager.kinds()); …
        }
    }

    public static CrdKind forKind(ResourceKind kind) {
        return Holder.ALL.get(kind);
    }

    /** The browsable kind for a CRD {@code kind} string like "HTTPRoute"; null if none. */
    public static ResourceKind byKindName(String kindName) {
        for (var e : Holder.ALL.entrySet()) {
            if (e.getValue().kind().equals(kindName)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** The distinct API groups the registered modules need (to probe which are installed). */
    public static Set<String> groups() {
        Set<String> out = new LinkedHashSet<>();
        for (CrdKind k : Holder.ALL.values()) {
            out.add(k.group());
        }
        return out;
    }
}
