package com.toolhelper.domain.kylin;

import java.util.List;

public record RollbackPlan(List<String> backedUpPaths, boolean available) {
    public RollbackPlan { backedUpPaths = backedUpPaths == null ? List.of() : List.copyOf(backedUpPaths); }
}
