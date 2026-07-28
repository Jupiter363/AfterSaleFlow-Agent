package com.example.dispute.workflow.targete2e.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.temporal.testing.TestWorkflowEnvironment;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class TargetTypedRoomCaseProcessDispatcherTest {

  @Test
  void ordinaryArtifactKeepsTheDispatcherAbstract() {
    assertThat(Modifier.isAbstract(TargetTypedRoomCaseProcessDispatcher.class.getModifiers()))
        .isTrue();
  }

  @Test
  void targetOnlyConcreteSubclassAndFrozenRoomTypesRegisterWithTemporal() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      var caseWorker = environment.newWorker("target-case-registration-test");
      var roomWorker = environment.newWorker("target-room-registration-test");

      assertThatCode(
              () ->
                  caseWorker.registerWorkflowImplementationTypes(
                      ConcreteTargetCaseProcessWorkflow.class))
          .doesNotThrowAnyException();
      assertThatCode(
              () ->
                  roomWorker.registerWorkflowImplementationTypes(
                      TargetTypedRoomProtocol.additionalWorkflowImplementations()
                          .toArray(Class[]::new)))
          .doesNotThrowAnyException();
    }
  }

  public static final class ConcreteTargetCaseProcessWorkflow
      extends TargetTypedRoomCaseProcessDispatcher {

    @Override
    protected boolean targetArtifactPresent() {
      return true;
    }
  }
}
