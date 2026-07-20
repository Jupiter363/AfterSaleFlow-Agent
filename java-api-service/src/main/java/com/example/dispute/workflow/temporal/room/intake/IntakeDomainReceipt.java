package com.example.dispute.workflow.temporal.room.intake;

import java.util.regex.Pattern;

public record IntakeDomainReceipt(
    String schemaVersion,
    String receiptId,
    String commandId,
    String tenantSurrogate,
    String caseId,
    long roomEpoch,
    long fencingToken,
    long eventSequence,
    long processRevision,
    long roomRevision,
    IntakeReceiptType receiptType,
    IntakeParty party,
    String operationKey,
    String requestHash,
    String resultHash,
    String receiptHash) {

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  public IntakeDomainReceipt {
    if (!"intake-domain-receipt.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be intake-domain-receipt.v1");
    }
    requireIdentifier(receiptId, "receiptId");
    requireIdentifier(commandId, "commandId");
    requireIdentifier(tenantSurrogate, "tenantSurrogate");
    requireIdentifier(caseId, "caseId");
    requireIdentifier(operationKey, "operationKey");
    requireHash(requestHash, "requestHash");
    requireHash(resultHash, "resultHash");
    requireHash(receiptHash, "receiptHash");
    if (roomEpoch < 0 || fencingToken < 1 || eventSequence < 1) {
      throw new IllegalArgumentException("epoch, fence, and event sequence must be valid");
    }
    if (processRevision < 0 || roomRevision < 0) {
      throw new IllegalArgumentException("revisions must not be negative");
    }
    if (receiptType == null || party == null) {
      throw new IllegalArgumentException("receiptType and party must not be null");
    }
  }

  private static void requireIdentifier(String value, String field) {
    if (value == null || !IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a bounded identifier");
    }
  }

  private static void requireHash(String value, String field) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a SHA-256 value");
    }
  }
}
