package com.kubedesk.service.crd;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Null-safe readers for the untyped maps/lists inside a {@link GenericKubernetesResource}. */
public final class Json {
    private Json() {
    }

    /** Walk {@code path} (map keys) from the resource's top level; returns null if any step is missing. */
    public static Object at(GenericKubernetesResource r, String... path) {
        Object cur = r.getAdditionalProperties();
        return walk(cur, path);
    }

    public static Object walk(Object cur, String... path) {
        for (String key : path) {
            if (cur instanceof Map<?, ?> m) {
                cur = m.get(key);
            } else {
                return null;
            }
        }
        return cur;
    }

    public static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** The value at {@code path} as a string, or "" when absent. */
    public static String str(GenericKubernetesResource r, String... path) {
        return str(at(r, path));
    }

    /** The value at {@code path} as a list of maps (non-map entries dropped); empty when absent. */
    public static List<Map<String, Object>> maps(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object e : l) {
                if (e instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) m;
                    out.add(cast);
                }
            }
        }
        return out;
    }

    public static List<Map<String, Object>> maps(GenericKubernetesResource r, String... path) {
        return maps(at(r, path));
    }

    public static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object e : l) {
                if (e != null) {
                    out.add(String.valueOf(e));
                }
            }
        }
        return out;
    }

    /** Status of a condition (e.g. "Accepted") in a {@code conditions} list: "True", "False", or "-". */
    public static String condition(List<Map<String, Object>> conditions, String type) {
        for (Map<String, Object> c : conditions) {
            if (type.equals(c.get("type"))) {
                return str(c.get("status"));
            }
        }
        return "-";
    }
}
