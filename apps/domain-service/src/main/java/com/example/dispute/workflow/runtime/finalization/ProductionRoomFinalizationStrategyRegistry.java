package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import java.util.List;
import java.util.Objects;

/** Resolves exactly one target finalization strategy; missing and ambiguous routing fail closed. */
public final class ProductionRoomFinalizationStrategyRegistry {

    private final List<ProductionRoomFinalizationStrategy> strategies;

    public ProductionRoomFinalizationStrategyRegistry(
            List<ProductionRoomFinalizationStrategy> strategies) {
        this.strategies = List.copyOf(Objects.requireNonNull(strategies, "strategies"));
        if (this.strategies.isEmpty()) {
            throw new IllegalArgumentException("at least one target room finalization strategy is required");
        }
    }

    public ProductionRoomFinalizationStrategy require(ExecuteAgentRunRequest request) {
        Objects.requireNonNull(request, "request");
        var roomType = request.command().roomType();
        List<ProductionRoomFinalizationStrategy> matches = strategies.stream()
                .filter(strategy -> strategy.roomType() == roomType)
                .filter(strategy -> strategy.supports(request))
                .toList();
        if (matches.size() != 1) {
            throw new ProductionFinalizationRejectedException(
                    "PRODUCTION_RUNTIME_ROOM_FINALIZATION_STRATEGY_INVALID",
                    "expected exactly one target finalization strategy for " + roomType);
        }
        return matches.getFirst();
    }
}
