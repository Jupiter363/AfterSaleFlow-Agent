package com.example.dispute.workflow.infrastructure.persistence.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.dispute.evidence.infrastructure.persistence.JdbcEvidenceFinalizationReceiptLedger;
import com.example.dispute.workflow.infrastructure.persistence.JdbcEvidenceFinalizationProjectionReader;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionQuery.StateEnricher;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationalRecoveryReconciler;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;

class EvidenceProjectionReadConfigurationTest {

  @Test
  void registersOnlyTheSelectReaderForTerminalAndRecoveryEnrichment() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          NamedParameterJdbcOperations.class,
          () -> mock(NamedParameterJdbcOperations.class));
      context.registerBean(
          PlatformTransactionManager.class,
          () -> mock(PlatformTransactionManager.class));
      context.register(EvidenceProjectionReadConfiguration.class);
      context.refresh();

      assertThat(context.getBeansOfType(StateEnricher.class))
          .containsOnlyKeys("evidenceFinalizationProjectionReader");
      Object reader = context.getBean("evidenceFinalizationProjectionReader");
      assertThat(reader).isInstanceOf(JdbcEvidenceFinalizationProjectionReader.class);
      assertThat(reader)
          .isInstanceOf(
              EvidenceOperationalRecoveryReconciler.EvidenceOperationalRecoveryStateEnricher
                  .DurableRecoveryStateReader.class);
      assertThat(context.getBeansOfType(JdbcEvidenceFinalizationReceiptLedger.class)).isEmpty();
    }
  }
}
