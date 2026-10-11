package com.sluice.balancing;

import java.util.List;
import java.util.Optional;

public interface LoadBalancer {

    Optional<CompiledTarget> choose(List<CompiledTarget> candidates, String hashKey);
}
