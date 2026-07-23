package com.example.dispute.workflow.infrastructure.persistence.config;

import com.example.dispute.evidence.application.graph.EvidenceCurrentAuthoritySnapshot.GraphLeaseAuthority;
import com.example.dispute.workflow.infrastructure.persistence.JdbcEvidenceFinalizationProjectionReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;

/** Production read-side wiring. No Evidence finalization writer is registered here. */
@Configuration(proxyBeanMethods = false)
public class EvidenceProjectionReadConfiguration {

  @Bean
  JdbcEvidenceFinalizationProjectionReader evidenceFinalizationProjectionReader(
      NamedParameterJdbcOperations jdbc,
      PlatformTransactionManager transactionManager,
      ObjectProvider<GraphLeaseAuthority> graphLeaseAuthorities) {
    return new JdbcEvidenceFinalizationProjectionReader(
        jdbc, transactionManager, graphLeaseAuthorities);
  }

}
