package com.example.dispute.workflow.targete2e.rooms.intake;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.room.application.IntakeConfirmationCommand;
import com.example.dispute.workflow.application.authority.payload.IntakeBranchCommand;
import com.example.dispute.workflow.application.intake.IntakeFinalizationPersistenceException;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeFormalBranchCommandResolver;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Resolves only the target-owned, content-addressed formal Intake branch command object. */
public final class TargetE2eIntakeFormalBranchCommandResolver
    implements IntakeFormalBranchCommandResolver {

  // Mirrors the target API publisher's fixed exchange namespace.
  public static final String TARGET_INTAKE_BUCKET = "target-e2e-intake-activation";
  public static final String TARGET_INTAKE_PREFIX = "browser-messages";
  private static final int MAX_BYTES = 16 * 1024;

  private final MinioClient minio;
  private final ObjectMapper mapper;
  private final String bucket;
  private final String prefix;

  public TargetE2eIntakeFormalBranchCommandResolver(
      MinioClient minio, ObjectMapper mapper, String bucket, String prefix) {
    this.minio = Objects.requireNonNull(minio, "minio");
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
      throw new IllegalArgumentException("target branch command bucket is invalid");
    }
    if (prefix == null || !prefix.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")) {
      throw new IllegalArgumentException("target branch command prefix is invalid");
    }
    this.bucket = bucket;
    this.prefix = prefix;
  }

  @Override
  public ResolvedBranchCommand resolve(BranchCommitRequest request) {
    Objects.requireNonNull(request, "request");
    Reference reference = parseReference(request);
    byte[] bytes = load(reference);
    if (bytes.length == 0 || bytes.length > MAX_BYTES) {
      throw rejected("TARGET_E2E_BRANCH_COMMAND_SIZE_INVALID", "branch command object size is invalid");
    }
    if (!reference.hash().equals(sha256(bytes))
        || !reference.hash().equals(request.envelope().commandPayloadHash())) {
      throw rejected("TARGET_E2E_BRANCH_COMMAND_HASH_MISMATCH", "branch command object hash differs from request");
    }

    IntakeBranchCommand command = decodeCanonical(bytes);
    requireBinding(request, command);
    IntakeConfirmationCommand confirmation = command.operation() == IntakeBranchCommand.Operation.CANCEL
        ? null
        : new IntakeConfirmationCommand(
            command.admissible(), command.disputeType(),
            RiskLevel.valueOf(command.riskLevel().name()), command.confirmationNote());
    return new ResolvedBranchCommand(
        request.operation(),
        IntakeBranchCommand.SCHEMA_VERSION,
        request.envelope().commandPayloadRef(),
        request.envelope().commandPayloadHash(),
        confirmation,
        command.cancellationReason());
  }

  private Reference parseReference(BranchCommitRequest request) {
    String value = request.envelope().commandPayloadRef();
    try {
      URI uri = URI.create(value);
      if (!"minio".equals(uri.getScheme()) || !bucket.equals(uri.getHost())
          || uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null
          || uri.getPort() != -1) {
        throw rejected("TARGET_E2E_BRANCH_COMMAND_REFERENCE_INVALID", "branch command reference is outside the target object store");
      }
      String path = uri.getRawPath();
      if (path == null || !path.equals(uri.getPath())) {
        throw rejected("TARGET_E2E_BRANCH_COMMAND_REFERENCE_INVALID", "branch command reference must not be encoded");
      }
      String expectedPrefix = "/" + prefix + "/" + IntakeBranchCommand.SCHEMA_VERSION + "/";
      if (!path.startsWith(expectedPrefix)) {
        throw rejected("TARGET_E2E_BRANCH_COMMAND_REFERENCE_INVALID", "branch command reference is outside the allowlisted prefix");
      }
      String remainder = path.substring(expectedPrefix.length());
      String[] segments = remainder.split("/", -1);
      if (segments.length != 2 || !segments[0].matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
          || !segments[1].matches("[0-9a-f]{64}\\.json")) {
        throw rejected("TARGET_E2E_BRANCH_COMMAND_REFERENCE_INVALID", "branch command reference layout is invalid");
      }
      String hash = segments[1].substring(0, 64);
      if (!hash.equals(request.envelope().commandPayloadHash())) {
        throw rejected("TARGET_E2E_BRANCH_COMMAND_REFERENCE_INVALID", "branch command reference does not bind request hash");
      }
      return new Reference(path.substring(1), hash);
    } catch (IllegalArgumentException failure) {
      throw rejected("TARGET_E2E_BRANCH_COMMAND_REFERENCE_INVALID", "branch command reference is malformed");
    }
  }

  private byte[] load(Reference reference) {
    try (var input = minio.getObject(
        GetObjectArgs.builder().bucket(bucket).object(reference.objectKey()).build())) {
      return input.readNBytes(MAX_BYTES + 1);
    } catch (Exception failure) {
      throw new IntakeFinalizationPersistenceException(
          "target branch command object is temporarily unavailable", failure);
    }
  }

  private IntakeBranchCommand decodeCanonical(byte[] bytes) {
    try {
      JsonNode document = mapper.readTree(bytes);
      if (document == null || !document.isObject()
          || !MessageDigest.isEqual(bytes, ContractJson.canonicalize(document))) {
        throw rejected("TARGET_E2E_BRANCH_COMMAND_CANONICAL_INVALID", "branch command is not canonical JSON");
      }
      return mapper.treeToValue(document, IntakeBranchCommand.class);
    } catch (IntakeFinalizationRejectedException failure) {
      throw failure;
    } catch (Exception failure) {
      throw rejected("TARGET_E2E_BRANCH_COMMAND_MALFORMED", "branch command is malformed");
    }
  }

  private static void requireBinding(BranchCommitRequest request, IntakeBranchCommand command) {
    BranchOperation operation;
    try {
      operation = BranchOperation.valueOf(command.operation().name());
    } catch (IllegalArgumentException failure) {
      throw rejected("TARGET_E2E_BRANCH_COMMAND_BINDING_INVALID", "branch command operation is invalid");
    }
    if (!IntakeBranchCommand.SCHEMA_VERSION.equals(command.schemaVersion())
        || !request.envelope().commandId().equals(command.commandId())
        || !request.envelope().commandType().name().equals(command.commandType().name())
        || !request.envelope().party().name().equals(command.party().name())
        || request.operation() != operation) {
      throw rejected("TARGET_E2E_BRANCH_COMMAND_BINDING_INVALID", "branch command does not match the exact request");
    }
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private static IntakeFinalizationRejectedException rejected(String code, String message) {
    return new IntakeFinalizationRejectedException(code, message);
  }

  private record Reference(String objectKey, String hash) {}
}
