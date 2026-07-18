package com.example.dispute.workflow.config;

import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochBootstrapEnqueuer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

@Component
public final class OrchestrationCutoverReadiness implements SmartInitializingSingleton {

    private final OrchestrationCutoverProperties cutoverProperties;
    private final RoomEpochBootstrapProperties bootstrapProperties;
    private final ObjectProvider<RoomEpochBootstrapEnqueuer> bootstrapEnqueuer;

    public OrchestrationCutoverReadiness(
            OrchestrationCutoverProperties cutoverProperties,
            RoomEpochBootstrapProperties bootstrapProperties,
            ObjectProvider<RoomEpochBootstrapEnqueuer> bootstrapEnqueuer) {
        this.cutoverProperties = cutoverProperties;
        this.bootstrapProperties = bootstrapProperties;
        this.bootstrapEnqueuer = bootstrapEnqueuer;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (cutoverProperties.newEpochMode() == WriterMode.LEGACY) {
            return;
        }
        if (!bootstrapProperties.enabled()) {
            throw new IllegalStateException(
                    "non-LEGACY epoch mode requires room epoch bootstrap recovery to be enabled");
        }
        if (bootstrapEnqueuer.getIfAvailable() == null) {
            throw new IllegalStateException(
                    "non-LEGACY epoch mode requires a durable room epoch bootstrap enqueuer");
        }
    }
}
