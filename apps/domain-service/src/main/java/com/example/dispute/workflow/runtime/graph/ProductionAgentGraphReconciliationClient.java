package com.example.dispute.workflow.runtime.graph;

import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.GraphReconciliationException;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import java.util.Objects;

/** Production-only result reconciliation that re-seals the exact durable AgentRun command. */
public final class ProductionAgentGraphReconciliationClient
    implements AgentGraphReconciliationClient {

  private final String activationId;
  private final ProductionAgentRunIdentityResolver identityResolver;
  private final ProductionGraphEnvelopeCodec codec;
  private final ProductionGraphEnvelopeSigner signer;
  private final HttpProductionGraphReconciliationClient client;
  private final GraphRegistryBindingPolicy registryBindingPolicy;

  public ProductionAgentGraphReconciliationClient(
      String activationId,
      ProductionAgentRunIdentityResolver identityResolver,
      ProductionGraphEnvelopeCodec codec,
      ProductionGraphEnvelopeSigner signer,
      HttpProductionGraphReconciliationClient client,
      GraphRegistryBindingPolicy registryBindingPolicy) {
    ProductionGraphCommandEnvelope.requirePattern(
        activationId, ProductionGraphCommandEnvelope.ACTIVATION_ID, "activationId");
    this.activationId = activationId;
    this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.signer = Objects.requireNonNull(signer, "signer");
    this.client = Objects.requireNonNull(client, "client");
    this.registryBindingPolicy =
        Objects.requireNonNull(registryBindingPolicy, "registryBindingPolicy");
  }

  @Override
  public GraphReconcileResponse reconcile(
      ExecuteAgentRunRequest request, AgentRunCancellationToken cancellationToken) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(cancellationToken, "cancellationToken").throwIfCancellationRequested();
    try {
      long roomFencingToken =
          Objects.requireNonNull(
                  identityResolver.resolve(request),
                  "durable AgentRun identity resolver returned no identity")
              .requireExact(request);
      RoomGraphCommand command = request.command();
      GraphRegistryBindingPolicy.ExpectedBinding binding =
          GraphRegistryBindingPolicy.requireExpected(
              registryBindingPolicy, GraphStreamVisibilityPolicy.Binding.from(command));
      ProductionSealedGraphCommand sealed =
          codec.sealCommand(activationId, roomFencingToken, command, binding, signer);
      HttpProductionGraphReconciliationClient.ReconciledResult reconciled =
          client.reconcileAvailable(sealed, cancellationToken);
      RoomGraphResult result = reconciled.envelope().result();
      return new GraphReconcileResponse(
          "graph-reconcile-response.v1",
          GraphReconcileResponse.Disposition.RECONCILED_TERMINAL,
          command.threadId(),
          command.commandId(),
          command.requestHash(),
          request.logicalRunId(),
          request.attemptId(),
          command.graphKey(),
          command.graphVersion(),
          command.checkpointSchemaVersion(),
          "",
          result.checkpointId(),
          reconciled.resultRef(),
          result.outputHash(),
          binding.registryBindingHash(),
          binding.toolPolicyVersion(),
          result);
    } catch (ProductionGraphClientException failure) {
      cancellationToken.throwIfCancellationRequested();
      boolean retryable =
          failure.recoveryAction()
              != ProductionGraphClientException.RecoveryAction.FAIL_LOGICAL_RUN;
      throw new GraphReconciliationException(
          failure.errorCode(),
          0,
          retryable,
          retryable
              ? AgentRunRecoveryAction.RETRY_SAME_COMMAND
              : AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
          "target Graph result reconciliation failed",
          failure);
    } catch (GraphReconciliationException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      cancellationToken.throwIfCancellationRequested();
      throw GraphReconciliationException.protocol(
          "target Graph reconciliation binding is invalid", failure);
    }
  }
}
