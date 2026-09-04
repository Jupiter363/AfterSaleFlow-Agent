package com.example.dispute.workflow.runtime.graph;

import java.util.Objects;

/** Immutable wire body and credential reused verbatim for retry and reconciliation. */
public final class ProductionSealedGraphCommand {

  private final ProductionGraphCommandEnvelope envelope;
  private final byte[] body;
  private final ProductionGraphEnvelopeSigner.SignedEnvelope credential;

  ProductionSealedGraphCommand(
      ProductionGraphCommandEnvelope envelope,
      byte[] body,
      ProductionGraphEnvelopeSigner.SignedEnvelope credential) {
    this.envelope = Objects.requireNonNull(envelope, "envelope");
    this.body = Objects.requireNonNull(body, "body").clone();
    this.credential = Objects.requireNonNull(credential, "credential");
    if (body.length == 0 || body.length > 65_536) {
      throw new IllegalArgumentException("sealed target Graph command body is invalid");
    }
  }

  public ProductionGraphCommandEnvelope envelope() {
    return envelope;
  }

  public byte[] body() {
    return body.clone();
  }

  public ProductionGraphEnvelopeSigner.SignedEnvelope credential() {
    return credential;
  }
}
