package com.example.dispute.workflow.infrastructure.outbox;

import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.workflow.application.command.CaseCommandDeliveryTrigger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CommandOutboxConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "app.orchestration.command-outbox.enabled",
            havingValue = "true")
    CaseCommandDeliveryTrigger postCommitCaseCommandDeliveryTrigger(
            PostCommitSideEffectExecutor postCommit,
            TemporalCommandDispatcher dispatcher) {
        return new PostCommitCaseCommandDeliveryTrigger(postCommit, dispatcher);
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.orchestration.command-outbox.enabled",
            havingValue = "false",
            matchIfMissing = true)
    CaseCommandDeliveryTrigger disabledCaseCommandDeliveryTrigger() {
        return outboxId -> {};
    }
}
