package com.example.dispute.workflow.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.AuthoritativeProcessObservation;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReconciliationTarget;
import com.example.dispute.workflow.application.projection.IntakeProcessProjectionCompletionService;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionCommand;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class IntakeProcessProjectionCompletionServiceTransactionTest {

    @Test
    void recoveryUsesAnIndependentTransaction() throws NoSuchMethodException {
        Method recover =
                IntakeProcessProjectionCompletionService.class.getMethod(
                        "recover",
                        ReconciliationTarget.class,
                        AuthoritativeProcessObservation.class);

        Transactional transaction = recover.getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void primaryCompletionUsesAnIndependentTransaction() throws NoSuchMethodException {
        Method complete =
                IntakeProcessProjectionCompletionService.class.getMethod(
                        "completeConsumedEvent",
                        CompleteConsumedIntakeProjectionCommand.class);

        Transactional transaction = complete.getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
