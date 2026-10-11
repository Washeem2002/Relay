package com.sluice.balancing;

import static org.assertj.core.api.Assertions.assertThat;

import com.sluice.support.Targets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoadBalancerTest {

    @Test
    void weightedRoundRobinHonoursWeightsAndInterleaves() {
        List<CompiledTarget> targets = List.of(Targets.target("a", 5), Targets.target("b", 1), Targets.target("c", 1));
        WeightedRoundRobinBalancer balancer = new WeightedRoundRobinBalancer();
        Map<String, Integer> counts = new HashMap<>();
        List<String> sequence = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            String id = balancer.choose(targets, "k").orElseThrow().id();
            counts.merge(id, 1, Integer::sum);
            sequence.add(id);
        }
        assertThat(counts).containsEntry("a", 50).containsEntry("b", 10).containsEntry("c", 10);
        assertThat(sequence.subList(0, 7)).containsExactlyInAnyOrder("a", "a", "a", "a", "a", "b", "c");
        assertThat(sequence.subList(0, 5)).contains("b").contains("c");
    }

    @Test
    void weightedRoundRobinHandlesChangingCandidateSet() {
        CompiledTarget a = Targets.target("a", 1);
        CompiledTarget b = Targets.target("b", 1);
        WeightedRoundRobinBalancer balancer = new WeightedRoundRobinBalancer();
        assertThat(balancer.choose(List.of(a), "k").orElseThrow().id()).isEqualTo("a");
        assertThat(balancer.choose(List.of(a, b), "k")).isPresent();
        assertThat(balancer.choose(List.of(), "k")).isEmpty();
    }

    @Test
    void leastConnectionsPicksLeastLoadedRelativeToWeight() {
        CompiledTarget a = Targets.target("a", 1);
        CompiledTarget b = Targets.target("b", 4);
        a.state().connectionOpened();
        a.state().connectionOpened();
        for (int i = 0; i < 5; i++) {
            b.state().connectionOpened();
        }
        LeastConnectionsBalancer balancer = new LeastConnectionsBalancer();
        assertThat(balancer.choose(List.of(a, b), "k").orElseThrow().id()).isEqualTo("b");
        b.state().connectionOpened();
        b.state().connectionOpened();
        b.state().connectionOpened();
        assertThat(balancer.choose(List.of(a, b), "k").orElseThrow().id()).isEqualTo("a");
        a.state().connectionClosed();
        a.state().connectionClosed();
        assertThat(a.state().activeConnections()).isZero();
        assertThat(balancer.choose(List.of(), "k")).isEmpty();
    }

    @Test
    void consistentHashIsDeterministicForTheSameKey() {
        List<CompiledTarget> targets = List.of(Targets.target("a", 1), Targets.target("b", 1), Targets.target("c", 1));
        ConsistentHashBalancer balancer = new ConsistentHashBalancer(targets);
        for (int i = 0; i < 200; i++) {
            String key = "client-" + i;
            assertThat(balancer.choose(targets, key).orElseThrow().id())
                    .isEqualTo(balancer.choose(targets, key).orElseThrow().id());
        }
    }

    @Test
    void consistentHashSpreadsKeysAcrossTargets() {
        List<CompiledTarget> targets = List.of(Targets.target("a", 1), Targets.target("b", 1), Targets.target("c", 1));
        ConsistentHashBalancer balancer = new ConsistentHashBalancer(targets);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 9000; i++) {
            counts.merge(balancer.choose(targets, "key-" + i).orElseThrow().id(), 1, Integer::sum);
        }
        assertThat(counts.values()).allSatisfy(count -> assertThat(count).isBetween(2400, 3600));
    }

    @Test
    void consistentHashOnlyRemapsKeysOwnedByTheRemovedTarget() {
        CompiledTarget a = Targets.target("a", 1);
        CompiledTarget b = Targets.target("b", 1);
        CompiledTarget c = Targets.target("c", 1);
        List<CompiledTarget> all = List.of(a, b, c);
        ConsistentHashBalancer ring = new ConsistentHashBalancer(all);
        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < 3000; i++) {
            before.put("key-" + i, ring.choose(all, "key-" + i).orElseThrow().id());
        }
        List<CompiledTarget> withoutB = List.of(a, c);
        int moved = 0;
        for (Map.Entry<String, String> entry : before.entrySet()) {
            String after = ring.choose(withoutB, entry.getKey()).orElseThrow().id();
            if (entry.getValue().equals("b")) {
                assertThat(after).isIn("a", "c");
            } else {
                assertThat(after).isEqualTo(entry.getValue());
            }
            if (!after.equals(entry.getValue())) {
                moved++;
            }
        }
        long ownedByB = before.values().stream().filter("b"::equals).count();
        assertThat((long) moved).isEqualTo(ownedByB);
    }

    @Test
    void consistentHashRebuiltWithoutTargetKeepsOtherMappings() {
        CompiledTarget a = Targets.target("a", 1);
        CompiledTarget b = Targets.target("b", 1);
        CompiledTarget c = Targets.target("c", 1);
        ConsistentHashBalancer full = new ConsistentHashBalancer(List.of(a, b, c));
        ConsistentHashBalancer reduced = new ConsistentHashBalancer(List.of(a, c));
        for (int i = 0; i < 2000; i++) {
            String key = "key-" + i;
            String owner = full.choose(List.of(a, b, c), key).orElseThrow().id();
            if (!owner.equals("b")) {
                assertThat(reduced.choose(List.of(a, c), key).orElseThrow().id()).isEqualTo(owner);
            }
        }
    }

    @Test
    void consistentHashWeightsSkewDistribution() {
        CompiledTarget heavy = Targets.target("heavy", 3);
        CompiledTarget light = Targets.target("light", 1);
        List<CompiledTarget> all = List.of(heavy, light);
        ConsistentHashBalancer balancer = new ConsistentHashBalancer(all);
        int heavyCount = 0;
        for (int i = 0; i < 8000; i++) {
            if (balancer.choose(all, "key-" + i).orElseThrow().id().equals("heavy")) {
                heavyCount++;
            }
        }
        assertThat(heavyCount).isBetween(5600, 6400);
    }

    @Test
    void consistentHashHandlesEmptyCandidates() {
        ConsistentHashBalancer balancer = new ConsistentHashBalancer(List.of(Targets.target("a", 1)));
        assertThat(balancer.choose(List.of(), "k")).isEmpty();
        assertThat(new ConsistentHashBalancer(List.of()).choose(List.of(Targets.target("a", 1)), "k")).isEmpty();
    }
}
