package com.sluice.routing;

import com.sluice.schema.RouteConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RouteMatcher {

    private static final int HOST_RANK_EXACT = 3;
    private static final int HOST_RANK_WILDCARD = 2;
    private static final int HOST_RANK_ANY = 1;

    private static final Comparator<RouteConfig> WITHIN_NODE_ORDER = Comparator
            .comparing((RouteConfig route) -> route.methods().isEmpty())
            .thenComparing(RouteConfig::name);

    private static final class Node {
        private final Map<String, Node> children = new HashMap<>();
        private final List<RouteConfig> routes = new ArrayList<>();
    }

    private record WildcardHost(String suffix, Node root) {
    }

    private record Candidate(int depth, int hostRank, int suffixLength, boolean methodSpecific, RouteConfig route) {
    }

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparingInt(Candidate::depth).reversed()
            .thenComparing(Comparator.comparingInt(Candidate::hostRank).reversed())
            .thenComparing(Comparator.comparingInt(Candidate::suffixLength).reversed())
            .thenComparing(Comparator.comparing(Candidate::methodSpecific).reversed())
            .thenComparing(candidate -> candidate.route().name());

    private final Map<String, Node> exactHosts;
    private final List<WildcardHost> wildcardHosts;
    private final Node anyHost;

    private RouteMatcher(Map<String, Node> exactHosts, List<WildcardHost> wildcardHosts, Node anyHost) {
        this.exactHosts = exactHosts;
        this.wildcardHosts = wildcardHosts;
        this.anyHost = anyHost;
    }

    public static RouteMatcher build(List<RouteConfig> routes) {
        Map<String, Node> exact = new HashMap<>();
        Map<String, Node> wildcard = new HashMap<>();
        Node any = new Node();
        for (RouteConfig route : routes) {
            Node root;
            if (route.host() == null || route.host().equals("*")) {
                root = any;
            } else if (route.host().startsWith("*.")) {
                root = wildcard.computeIfAbsent(route.host().substring(1), key -> new Node());
            } else {
                root = exact.computeIfAbsent(route.host(), key -> new Node());
            }
            insert(root, route);
        }
        sortNodes(any);
        exact.values().forEach(RouteMatcher::sortNodes);
        wildcard.values().forEach(RouteMatcher::sortNodes);
        List<WildcardHost> wildcards = new ArrayList<>();
        wildcard.forEach((suffix, root) -> wildcards.add(new WildcardHost(suffix, root)));
        wildcards.sort(Comparator.comparingInt((WildcardHost host) -> host.suffix().length()).reversed()
                .thenComparing(WildcardHost::suffix));
        return new RouteMatcher(exact, List.copyOf(wildcards), any);
    }

    public Optional<RouteConfig> match(String host, String path, String method) {
        List<String> segments = PathNormalizer.segments(path);
        List<Candidate> candidates = new ArrayList<>(3);
        Node exactRoot = exactHosts.get(host);
        if (exactRoot != null) {
            bestIn(exactRoot, segments, method, HOST_RANK_EXACT, host.length()).ifPresent(candidates::add);
        }
        for (WildcardHost wildcard : wildcardHosts) {
            if (host.endsWith(wildcard.suffix()) && host.length() > wildcard.suffix().length()) {
                bestIn(wildcard.root(), segments, method, HOST_RANK_WILDCARD, wildcard.suffix().length())
                        .ifPresent(candidates::add);
            }
        }
        bestIn(anyHost, segments, method, HOST_RANK_ANY, 0).ifPresent(candidates::add);
        return candidates.stream().min(CANDIDATE_ORDER).map(Candidate::route);
    }

    private static void insert(Node root, RouteConfig route) {
        Node node = root;
        for (String segment : PathNormalizer.segments(route.pathPrefix())) {
            node = node.children.computeIfAbsent(segment, key -> new Node());
        }
        node.routes.add(route);
    }

    private static void sortNodes(Node node) {
        node.routes.sort(WITHIN_NODE_ORDER);
        node.children.values().forEach(RouteMatcher::sortNodes);
    }

    private static Optional<Candidate> bestIn(Node root, List<String> segments, String method, int hostRank,
                                              int suffixLength) {
        List<Node> visited = new ArrayList<>(segments.size() + 1);
        visited.add(root);
        Node node = root;
        for (String segment : segments) {
            node = node.children.get(segment);
            if (node == null) {
                break;
            }
            visited.add(node);
        }
        for (int depth = visited.size() - 1; depth >= 0; depth--) {
            for (RouteConfig route : visited.get(depth).routes) {
                if (allows(route, method)) {
                    return Optional.of(new Candidate(depth, hostRank, suffixLength, !route.methods().isEmpty(), route));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean allows(RouteConfig route, String method) {
        if (route.methods().isEmpty() || route.methods().contains(method)) {
            return true;
        }
        return method.equals("HEAD") && route.methods().contains("GET");
    }
}
