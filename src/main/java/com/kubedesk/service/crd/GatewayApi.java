package com.kubedesk.service.crd;

import com.kubedesk.service.Dtos.CrdCondition;
import com.kubedesk.service.Dtos.CrdDetails;
import com.kubedesk.service.Dtos.CrdRelation;
import com.kubedesk.service.Fabric8ClusterService;
import io.fabric8.kubernetes.client.KubernetesClient;
import com.kubedesk.service.ResourceKind;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Kubernetes Gateway API ({@code gateway.networking.k8s.io}) — the successor to Ingress — as a
 * read-only CRD module: the four kinds KubeDesk shows, how each becomes a table row, and the helpers
 * the topology view needs. Adding another CRD family follows this same shape.
 */
public final class GatewayApi {
    private GatewayApi() {
    }

    public static final String GROUP = "gateway.networking.k8s.io";

    private static final List<String> STABLE = List.of("v1", "v1beta1");
    private static final List<String> GRPC = List.of("v1", "v1alpha2");

    public static final CrdKind GATEWAY_CLASS = new CrdKind(GROUP, "GatewayClass", "gatewayclasses", false, STABLE,
            List.of("Name", "Controller", "Accepted", "Description", "Age"),
            r -> List.of(name(r), Json.str(r, "spec", "controllerName"), cond(r, "Accepted"),
                    Json.str(r, "spec", "description"), age(r)),
            GatewayApi::inspectClass);

    public static final CrdKind GATEWAY = new CrdKind(GROUP, "Gateway", "gateways", true, STABLE,
            List.of("Name", "Class", "Address", "Listeners", "Programmed", "Age"),
            r -> List.of(name(r), Json.str(r, "spec", "gatewayClassName"), addresses(r), listeners(r),
                    cond(r, "Programmed"), age(r)),
            GatewayApi::inspectGateway);

    public static final CrdKind HTTP_ROUTE = new CrdKind(GROUP, "HTTPRoute", "httproutes", true, STABLE,
            routeColumns(), GatewayApi::routeRow, GatewayApi::inspectRoute);

    public static final CrdKind GRPC_ROUTE = new CrdKind(GROUP, "GRPCRoute", "grpcroutes", true, GRPC,
            routeColumns(), GatewayApi::routeRow, GatewayApi::inspectRoute);

    /** Which {@link ResourceKind} each Gateway API kind is browsed as. */
    public static Map<ResourceKind, CrdKind> kinds() {
        Map<ResourceKind, CrdKind> m = new EnumMap<>(ResourceKind.class);
        m.put(ResourceKind.GATEWAYCLASSES, GATEWAY_CLASS);
        m.put(ResourceKind.GATEWAYS, GATEWAY);
        m.put(ResourceKind.HTTPROUTES, HTTP_ROUTE);
        m.put(ResourceKind.GRPCROUTES, GRPC_ROUTE);
        return m;
    }

    // ---- topology helpers ----------------------------------------------------------------

    /** Names of the Services a route sends traffic to (backendRefs of kind Service, the default). */
    public static Set<String> backendServices(GenericKubernetesResource route) {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> rule : Json.maps(route, "spec", "rules")) {
            for (Map<String, Object> ref : Json.maps(rule.get("backendRefs"))) {
                String k = Json.str(ref.get("kind"));
                if ((k.isEmpty() || "Service".equals(k)) && ref.get("name") != null) {
                    out.add(Json.str(ref.get("name")));
                }
            }
        }
        return out;
    }

    /** True when {@code route} lists {@code gateway} as a parent (same-namespace unless the ref names one). */
    public static boolean routeAttachesTo(GenericKubernetesResource route, GenericKubernetesResource gateway) {
        for (Map<String, Object> pr : Json.maps(route, "spec", "parentRefs")) {
            if (refersToGateway(pr, route.getMetadata().getNamespace(),
                    gateway.getMetadata().getNamespace(), gateway.getMetadata().getName())) {
                return true;
            }
        }
        return false;
    }

    /** Hostnames a route serves, comma-joined ("*" when none are set). */
    public static String hostnames(GenericKubernetesResource route) {
        List<String> hs = Json.strings(Json.at(route, "spec", "hostnames"));
        return hs.isEmpty() ? "*" : String.join(", ", hs);
    }

    // ---- row builders --------------------------------------------------------------------

    private static List<String> routeColumns() {
        return List.of("Name", "Hostnames", "Gateways", "Backends", "Accepted", "Age");
    }

    private static List<String> routeRow(GenericKubernetesResource r) {
        return List.of(name(r), hostnames(r), parents(r), backends(r), routeAccepted(r), age(r));
    }

    private static String name(GenericKubernetesResource r) {
        return r.getMetadata().getName();
    }

    private static String age(GenericKubernetesResource r) {
        return Fabric8ClusterService.age(r.getMetadata().getCreationTimestamp());
    }

    private static String cond(GenericKubernetesResource r, String type) {
        return Json.condition(Json.maps(r, "status", "conditions"), type);
    }

    private static String addresses(GenericKubernetesResource r) {
        String s = Json.maps(r, "status", "addresses").stream()
                .map(a -> Json.str(a.get("value")))
                .filter(v -> !v.isBlank())
                .collect(Collectors.joining(", "));
        return s.isBlank() ? "-" : s;
    }

    private static String listeners(GenericKubernetesResource r) {
        String s = Json.maps(r, "spec", "listeners").stream()
                .map(l -> Json.str(l.get("protocol")).toLowerCase() + ":" + Json.str(l.get("port")))
                .collect(Collectors.joining(", "));
        return s.isBlank() ? "-" : s;
    }

    private static String parents(GenericKubernetesResource r) {
        String own = r.getMetadata().getNamespace();
        String s = Json.maps(r, "spec", "parentRefs").stream()
                .map(p -> {
                    String ns = Json.str(p.get("namespace"));
                    String n = Json.str(p.get("name"));
                    return (!ns.isBlank() && !ns.equals(own)) ? ns + "/" + n : n;
                })
                .collect(Collectors.joining(", "));
        return s.isBlank() ? "-" : s;
    }

    private static String backends(GenericKubernetesResource r) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> rule : Json.maps(r, "spec", "rules")) {
            for (Map<String, Object> ref : Json.maps(rule.get("backendRefs"))) {
                String n = Json.str(ref.get("name"));
                String port = Json.str(ref.get("port"));
                out.add(port.isBlank() ? n : n + ":" + port);
            }
        }
        String s = out.stream().distinct().collect(Collectors.joining(", "));
        return s.isBlank() ? "-" : s;
    }

    /** A route is Accepted per parent Gateway: True if all parents accept it, False if any refuses. */
    private static String routeAccepted(GenericKubernetesResource r) {
        List<String> states = new ArrayList<>();
        for (Map<String, Object> parent : Json.maps(r, "status", "parents")) {
            states.add(Json.condition(Json.maps(parent.get("conditions")), "Accepted"));
        }
        if (states.isEmpty()) {
            return "-";
        }
        if (states.contains("False")) {
            return "False";
        }
        return states.stream().allMatch("True"::equals) ? "True" : "Unknown";
    }

    // ---- status / relations (the details "Status" tab) ------------------------------------

    private static List<CrdCondition> conds(String scope, List<Map<String, Object>> conditions) {
        List<CrdCondition> out = new ArrayList<>();
        for (Map<String, Object> c : conditions) {
            out.add(new CrdCondition(scope, Json.str(c.get("type")), Json.str(c.get("status")),
                    Json.str(c.get("reason")), Json.str(c.get("message")),
                    Json.str(c.get("lastTransitionTime"))));
        }
        return out;
    }

    /** All routes (HTTP + gRPC) in every namespace; a missing/unreadable kind just contributes nothing. */
    private static List<GenericKubernetesResource> allRoutes(KubernetesClient c) {
        List<GenericKubernetesResource> out = new ArrayList<>();
        for (CrdKind k : List.of(HTTP_ROUTE, GRPC_ROUTE)) {
            try {
                out.addAll(CrdSupport.items(c, k, null));
            } catch (Exception ignored) {
                // CRD not installed / not permitted
            }
        }
        return out;
    }

    private static boolean refersToGateway(Map<String, Object> parentRef, String routeNs,
                                           String gwNs, String gwName) {
        String kind = Json.str(parentRef.get("kind"));
        if (!kind.isEmpty() && !"Gateway".equals(kind)) {
            return false;
        }
        String ns = Json.str(parentRef.get("namespace"));
        return gwName.equals(Json.str(parentRef.get("name"))) && (ns.isEmpty() ? routeNs : ns).equals(gwNs);
    }

    private static CrdDetails inspectClass(KubernetesClient c, GenericKubernetesResource gc) {
        List<CrdRelation> rel = new ArrayList<>();
        String name = gc.getMetadata().getName();
        try {
            for (GenericKubernetesResource gw : CrdSupport.items(c, GATEWAY, null)) {
                if (name.equals(Json.str(gw, "spec", "gatewayClassName"))) {
                    rel.add(new CrdRelation("Gateways using this class", "Gateway",
                            gw.getMetadata().getNamespace(), gw.getMetadata().getName(), addresses(gw)));
                }
            }
        } catch (Exception ignored) {
            // optional
        }
        return new CrdDetails(conds("", Json.maps(gc, "status", "conditions")), rel);
    }

    private static CrdDetails inspectGateway(KubernetesClient c, GenericKubernetesResource gw) {
        String ns = gw.getMetadata().getNamespace();
        String name = gw.getMetadata().getName();
        List<CrdCondition> conditions = new ArrayList<>(conds("", Json.maps(gw, "status", "conditions")));
        Map<String, String> attached = new java.util.HashMap<>();
        for (Map<String, Object> l : Json.maps(gw, "status", "listeners")) {
            conditions.addAll(conds("listener " + Json.str(l.get("name")), Json.maps(l.get("conditions"))));
            attached.put(Json.str(l.get("name")), Json.str(l.get("attachedRoutes")));
        }

        List<CrdRelation> rel = new ArrayList<>();
        String cls = Json.str(gw, "spec", "gatewayClassName");
        if (!cls.isBlank()) {
            rel.add(new CrdRelation("Class", "GatewayClass", "", cls, ""));
        }
        for (Map<String, Object> l : Json.maps(gw, "spec", "listeners")) {
            String lname = Json.str(l.get("name"));
            String host = Json.str(l.get("hostname"));
            String info = Json.str(l.get("protocol")) + " :" + Json.str(l.get("port"))
                    + (host.isBlank() ? "" : " · " + host)
                    + (attached.containsKey(lname) ? " · " + attached.get(lname) + " route(s) attached" : "");
            rel.add(new CrdRelation("Listeners", "", "", lname, info));
        }
        for (GenericKubernetesResource route : allRoutes(c)) {
            String rns = route.getMetadata().getNamespace();
            for (Map<String, Object> pr : Json.maps(route, "spec", "parentRefs")) {
                if (refersToGateway(pr, rns, ns, name)) {
                    rel.add(new CrdRelation("Attached routes", route.getKind(), rns,
                            route.getMetadata().getName(), hostnames(route)
                            + " · accepted: " + routeAcceptedBy(route, name)));
                    break;
                }
            }
        }
        for (Map<String, Object> l : Json.maps(gw, "spec", "listeners")) {
            Object tls = Json.walk(l, "tls");
            for (Map<String, Object> ref : Json.maps(Json.walk(tls, "certificateRefs"))) {
                String k = Json.str(ref.get("kind"));
                if (k.isEmpty() || "Secret".equals(k)) {
                    String sns = Json.str(ref.get("namespace"));
                    rel.add(new CrdRelation("TLS secrets", "Secret", sns.isEmpty() ? ns : sns,
                            Json.str(ref.get("name")), "listener " + Json.str(l.get("name"))));
                }
            }
        }
        return new CrdDetails(conditions, rel);
    }

    /** The Accepted status a route reports for one specific parent Gateway ("-" if it reports none). */
    private static String routeAcceptedBy(GenericKubernetesResource route, String gwName) {
        for (Map<String, Object> p : Json.maps(route, "status", "parents")) {
            if (gwName.equals(Json.str(Json.walk(p, "parentRef", "name")))) {
                return Json.condition(Json.maps(p.get("conditions")), "Accepted");
            }
        }
        return "-";
    }

    private static CrdDetails inspectRoute(KubernetesClient c, GenericKubernetesResource route) {
        String ns = route.getMetadata().getNamespace();
        List<CrdCondition> conditions = new ArrayList<>();
        for (Map<String, Object> p : Json.maps(route, "status", "parents")) {
            conditions.addAll(conds("gateway " + Json.str(Json.walk(p, "parentRef", "name")),
                    Json.maps(p.get("conditions"))));
        }
        List<CrdRelation> rel = new ArrayList<>();
        for (Map<String, Object> pr : Json.maps(route, "spec", "parentRefs")) {
            String k = Json.str(pr.get("kind"));
            if (!k.isEmpty() && !"Gateway".equals(k)) {
                continue;
            }
            String pns = Json.str(pr.get("namespace"));
            String pname = Json.str(pr.get("name"));
            rel.add(new CrdRelation("Parent gateways", "Gateway", pns.isEmpty() ? ns : pns, pname,
                    "accepted: " + routeAcceptedBy(route, pname)));
        }
        for (Map<String, Object> rule : Json.maps(route, "spec", "rules")) {
            for (Map<String, Object> ref : Json.maps(rule.get("backendRefs"))) {
                String k = Json.str(ref.get("kind"));
                String bns = Json.str(ref.get("namespace"));
                String w = Json.str(ref.get("weight"));
                rel.add(new CrdRelation("Backends", k.isEmpty() ? "Service" : k, bns.isEmpty() ? ns : bns,
                        Json.str(ref.get("name")), "port " + Json.str(ref.get("port"))
                        + (w.isBlank() ? "" : " · weight " + w)));
            }
        }
        return new CrdDetails(conditions, rel);
    }}
