package com.example.dispute.workflow.runtime.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionRoomFinalizationStrategyRegistryTest {

    @Test
    void requiresOneExactStrategyForTheRoomAndGraphContract() {
        var fixture = ProductionFinalizationFixture.valid();
        var intake = strategy(RoomType.INTAKE, request -> true);
        var registry = new ProductionRoomFinalizationStrategyRegistry(List.of(intake));

        assertThat(registry.require(fixture.request())).isSameAs(intake);
    }

    @Test
    void rejectsMissingOrAmbiguousStrategiesInsteadOfFallingBack() {
        var fixture = ProductionFinalizationFixture.valid();
        var missing = new ProductionRoomFinalizationStrategyRegistry(
                List.of(strategy(RoomType.INTAKE, request -> false)));
        var ambiguous = new ProductionRoomFinalizationStrategyRegistry(List.of(
                strategy(RoomType.INTAKE, request -> true),
                strategy(RoomType.INTAKE, request -> true)));

        assertThatThrownBy(() -> missing.require(fixture.request()))
                .isInstanceOf(ProductionFinalizationRejectedException.class)
                .hasMessageContaining("exactly one target finalization strategy");
        assertThatThrownBy(() -> ambiguous.require(fixture.request()))
                .isInstanceOf(ProductionFinalizationRejectedException.class)
                .hasMessageContaining("exactly one target finalization strategy");
    }

    private static ProductionRoomFinalizationStrategy strategy(
            RoomType roomType, java.util.function.Predicate<ExecuteAgentRunRequest> supported) {
        return new ProductionRoomFinalizationStrategy() {
            @Override
            public RoomType roomType() {
                return roomType;
            }

            @Override
            public boolean supports(ExecuteAgentRunRequest request) {
                return supported.test(request);
            }

            @Override
            public PreparedFinalization prepare(
                    ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
