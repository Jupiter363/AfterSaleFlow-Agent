package com.example.dispute.workflow.targete2e.graph;

import java.util.Objects;

/** Immutable wire body and credential reused verbatim for retry and reconciliation. */
public final class TargetE2ESealedGraphCommand {

  private final TargetE2EGraphCommandEnvelope envelope;
  private final byte[] body;
  private final TargetE2EGraphEnvelopeSigner.SignedEnvelope credential;

  TargetE2ESealedGraphCommand(
      TargetE2EGraphCommandEnvelope envelope,
      byte[] body,
      TargetE2EGraphEnvelopeSigner.SignedEnvelope credential) {
    this.envelope = Objects.requireNonNull(envelope, "envelope");
    this.body = Objects.requireNonNull(body, "body").clone();
    this.credential = Objects.requireNonNull(credential, "credential");
    if (body.length == 0 || body.length > 65_536) {
      throw new IllegalArgumentException("sealed target Graph command body is invalid");
    }
  }

  public TargetE2EGraphCommandEnvelope envelope() {
    return envelope;
  }

  public byte[] body() {
    return body.clone();
  }

  public TargetE2EGraphEnvelopeSigner.SignedEnvelope credential() {
    return credential;
  }
}
