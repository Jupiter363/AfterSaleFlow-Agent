package com.example.dispute.workflow.targete2e.artifact.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt.CommitStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.application.intake.IntakeFinalizationPersistenceException;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceipt;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceiptLedger.StoredReceipt;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eMultiRoomOuterFinalizer;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eMultiRoomOuterFinalizer.FinalizationOutcome;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.mockito.Mockito;

class TargetE2eMultiRoomFinalizationGatewayTest {

    @TempDir static Path classes;

    private static URLClassLoader classLoader;
    private static Class<?> relayType;
    private static Class<?> gatewayType;

    @BeforeAll
    static void compileGateway() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK compiler is required").isNotNull();
        Path sourceRoot = Path.of(
                "src",
                "target-e2e",
                "java",
                "com",
                "example",
                "dispute",
                "workflow",
                "targete2e",
                "artifact",
                "finalization");
        int status = compiler.run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classes.toString(),
                sourceRoot.resolve("TargetE2eIntakeDomainEventLiveRelay.java").toString(),
                sourceRoot.resolve("TargetE2eMultiRoomFinalizationGateway.java").toString());
        assertThat(status).isZero();
        classLoader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()},
                TargetE2eMultiRoomFinalizationGatewayTest.class.getClassLoader());
        relayType = classLoader.loadClass(
                "com.example.dispute.workflow.targete2e.artifact.finalization."
                        + "TargetE2eIntakeDomainEventLiveRelay");
        gatewayType = classLoader.loadClass(
                "com.example.dispute.workflow.targete2e.artifact.finalization."
                        + "TargetE2eMultiRoomFinalizationGateway");
    }

    @AfterAll
    static void closeLoader() throws Exception {
        classLoader.close();
    }

    @Test
    void signalsOnlyAfterTheOuterFinalizerHasReturnedItsCommittedOutcome() throws Exception {
        ExecuteAgentRunRequest request = request(RoomType.INTAKE);
        ExecuteAgentRunResult result = mock(ExecuteAgentRunResult.class);
        AgentRunFinalizationReceipt receipt = receipt(CommitStatus.COMMITTED);
        FinalizationOutcome outcome = outcome(receipt);
        AtomicBoolean committed = new AtomicBoolean();
        TargetE2eMultiRoomOuterFinalizer outer = mock(TargetE2eMultiRoomOuterFinalizer.class);
        when(outer.finalizeAgentRunResult(request, result)).thenAnswer(ignored -> {
            committed.set(true);
            return outcome;
        });
        AtomicInteger signals = new AtomicInteger();
        Object relay = relayMock(invocation -> {
            if (invocation.getMethod().getName().equals("relay")) {
                assertThat(committed).isTrue();
                signals.incrementAndGet();
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        Object gateway = newGateway(outer, relay);

        assertThat(invokeGateway(gateway, request, result)).isSameAs(receipt);
        assertThat(signals).hasValue(1);
        verify(outer).finalizeAgentRunResult(request, result);
    }

    @Test
    void alreadyCommittedReplayStillSignalsAtLeastOnce() throws Exception {
        ExecuteAgentRunRequest request = request(RoomType.INTAKE);
        ExecuteAgentRunResult result = mock(ExecuteAgentRunResult.class);
        AgentRunFinalizationReceipt receipt = receipt(CommitStatus.ALREADY_COMMITTED);
        TargetE2eMultiRoomOuterFinalizer outer = mock(TargetE2eMultiRoomOuterFinalizer.class);
        FinalizationOutcome outcome = outcome(receipt);
        when(outer.finalizeAgentRunResult(request, result)).thenReturn(outcome);
        AtomicInteger signals = new AtomicInteger();
        Object relay = relayMock(invocation -> {
            if (invocation.getMethod().getName().equals("relay")) {
                signals.incrementAndGet();
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        Object gateway = newGateway(outer, relay);

        assertThat(invokeGateway(gateway, request, result)).isSameAs(receipt);
        assertThat(signals).hasValue(1);
        verify(outer).finalizeAgentRunResult(request, result);
    }

    @Test
    void nonIntakeFinalizationNeverSignalsTheIntakeRelay() throws Exception {
        ExecuteAgentRunRequest request = request(RoomType.EVIDENCE);
        ExecuteAgentRunResult result = mock(ExecuteAgentRunResult.class);
        AgentRunFinalizationReceipt receipt = receipt(CommitStatus.COMMITTED);
        TargetE2eMultiRoomOuterFinalizer outer = mock(TargetE2eMultiRoomOuterFinalizer.class);
        FinalizationOutcome outcome = outcome(receipt);
        when(outer.finalizeAgentRunResult(request, result)).thenReturn(outcome);
        Object relay = relayMock(Answers.RETURNS_DEFAULTS);
        Object gateway = newGateway(outer, relay);

        assertThat(invokeGateway(gateway, request, result)).isSameAs(receipt);
        verifyNoInteractions(relay);
    }

    @Test
    void relayFailurePropagatesAfterCommitSoTheFinalizationActivityCanRetry() throws Exception {
        ExecuteAgentRunRequest request = request(RoomType.INTAKE);
        ExecuteAgentRunResult result = mock(ExecuteAgentRunResult.class);
        AgentRunFinalizationReceipt receipt = receipt(CommitStatus.COMMITTED);
        TargetE2eMultiRoomOuterFinalizer outer = mock(TargetE2eMultiRoomOuterFinalizer.class);
        FinalizationOutcome outcome = outcome(receipt);
        when(outer.finalizeAgentRunResult(request, result)).thenReturn(outcome);
        IntakeFinalizationPersistenceException signalFailure =
                new IntakeFinalizationPersistenceException(
                        "signal unavailable", new IllegalStateException("Temporal unavailable"));
        Object relay = relayMock(invocation -> {
            if (invocation.getMethod().getName().equals("relay")) {
                throw signalFailure;
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        Object gateway = newGateway(outer, relay);

        assertThatThrownBy(() -> invokeGateway(gateway, request, result))
                .isSameAs(signalFailure);
        verify(outer, times(1)).finalizeAgentRunResult(request, result);
    }

    private static Object newGateway(TargetE2eMultiRoomOuterFinalizer outer, Object relay)
            throws Exception {
        return gatewayType
                .getConstructor(TargetE2eMultiRoomOuterFinalizer.class, relayType)
                .newInstance(outer, relay);
    }

    private static AgentRunFinalizationReceipt invokeGateway(
            Object gateway, ExecuteAgentRunRequest request, ExecuteAgentRunResult result)
            throws Exception {
        try {
            return (AgentRunFinalizationReceipt)
                    gatewayType
                            .getMethod(
                                    "finalizeResult",
                                    ExecuteAgentRunRequest.class,
                                    ExecuteAgentRunResult.class)
                            .invoke(gateway, request, result);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }

    private static Object relayMock(org.mockito.stubbing.Answer<?> answer) {
        return Mockito.mock(relayType, answer);
    }

    private static ExecuteAgentRunRequest request(RoomType roomType) {
        ExecuteAgentRunRequest request = mock(ExecuteAgentRunRequest.class);
        RoomGraphCommand command = mock(RoomGraphCommand.class);
        when(request.command()).thenReturn(command);
        when(command.roomType()).thenReturn(roomType);
        return request;
    }

    private static FinalizationOutcome outcome(AgentRunFinalizationReceipt receipt) {
        TargetE2eFinalizationReceipt targetReceipt = mock(TargetE2eFinalizationReceipt.class);
        StoredReceipt stored = mock(StoredReceipt.class);
        when(stored.receipt()).thenReturn(targetReceipt);
        return new FinalizationOutcome(stored, receipt);
    }

    private static AgentRunFinalizationReceipt receipt(CommitStatus status) {
        AgentRunFinalizationReceipt receipt = mock(AgentRunFinalizationReceipt.class);
        when(receipt.commitStatus()).thenReturn(status);
        return receipt;
    }
}
