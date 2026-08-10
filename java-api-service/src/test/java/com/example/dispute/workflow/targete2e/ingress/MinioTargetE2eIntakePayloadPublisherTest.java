package com.example.dispute.workflow.targete2e.ingress;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher.PublishRequest;
import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.authority.epoch.EpochPartyAuthority.Party;
import com.example.dispute.workflow.application.authority.payload.IntakeBranchCommand;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class MinioTargetE2eIntakePayloadPublisherTest {

  private static final Path TARGET_API_CONFIGURATION =
      Path.of(
          "src/target-e2e/java/com/example/dispute/workflow/targete2e/artifact/"
              + "TargetE2eApiConfiguration.java");

  @Test
  void readinessIsEagerBoundedSameClientOnceFailClosedAndWriteFree() throws Exception {
    MinioClient minio = mock(MinioClient.class);
    when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    MinioTargetE2eIntakePayloadPublisher publisher =
        new MinioTargetE2eIntakePayloadPublisher(
            minio, "target-e2e-intake-activation", "browser-messages");

    assertThat(publisher.prepare()).isSameAs(publisher);
    assertThat(publisher.prepare()).isSameAs(publisher);
    ArgumentCaptor<BucketExistsArgs> bucketProbe =
        ArgumentCaptor.forClass(BucketExistsArgs.class);
    verify(minio, times(1)).bucketExists(bucketProbe.capture());
    assertThat(bucketProbe.getValue().bucket()).isEqualTo("target-e2e-intake-activation");
    verify(minio, never()).putObject(any(PutObjectArgs.class));

    var payload = JsonNodeFactory.instance.objectNode();
    payload.put("schema_version", "intake-domain-snapshot.v2");
    payload.put("snapshot_id", "snapshot-1");
    payload.put("snapshot_hash", "0".repeat(64));
    String hash = IntakeContractHashes.canonicalHashExcluding(payload, "snapshot_hash");
    payload.put("snapshot_hash", hash);
    byte[] canonical = ContractJson.canonicalize(payload);
    var stored =
        publisher.publish(
            new PublishRequest(
                "snapshot-1", "intake-domain-snapshot.v2", hash, canonical, 262_144));
    assertThat(stored.uri())
        .isEqualTo(
            "minio://target-e2e-intake-activation/browser-messages/"
                + "intake-domain-snapshot.v2/snapshot-1/"
                + hash
                + ".json");
    verify(minio, times(1)).putObject(any(PutObjectArgs.class));

    MinioClient missingClient = mock(MinioClient.class);
    when(missingClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
    MinioTargetE2eIntakePayloadPublisher missing =
        new MinioTargetE2eIntakePayloadPublisher(
            missingClient, "target-e2e-intake-activation", "browser-messages");
    Throwable missingFirst = catchThrowable(missing::prepare);
    Throwable missingSecond = catchThrowable(missing::prepare);
    assertThat(missingFirst)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("target Intake payload readiness failed");
    assertThat(missingSecond).isSameAs(missingFirst);
    verify(missingClient, times(1)).bucketExists(any(BucketExistsArgs.class));
    verify(missingClient, never()).putObject(any(PutObjectArgs.class));

    MinioClient failedClient = mock(MinioClient.class);
    IOException probeFailure = new IOException("probe failed");
    when(failedClient.bucketExists(any(BucketExistsArgs.class))).thenThrow(probeFailure);
    MinioTargetE2eIntakePayloadPublisher failed =
        new MinioTargetE2eIntakePayloadPublisher(
            failedClient, "target-e2e-intake-activation", "browser-messages");
    Throwable failedFirst = catchThrowable(failed::prepare);
    Throwable failedSecond = catchThrowable(failed::prepare);
    assertThat(failedFirst)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("target Intake payload readiness failed");
    assertThat(failedFirst.getCause()).isSameAs(probeFailure);
    assertThat(failedSecond).isSameAs(failedFirst);
    verify(failedClient, times(1)).bucketExists(any(BucketExistsArgs.class));
    verify(failedClient, never()).putObject(any(PutObjectArgs.class));

    String configuration = Files.readString(TARGET_API_CONFIGURATION);
    String publisherBean = beanMethod(configuration, "targetE2eIntakePayloadPublisher");
    String pinsBean = beanMethod(configuration, "targetIntakeRuntimePins");
    String materializerBean = beanMethod(configuration, "targetIntakeMaterializer");
    String ingressBean = beanMethod(configuration, "targetTemporalIntakeIngress");
    assertThat(publisherBean)
        .contains("@Lazy(false)")
        .contains("MinioTargetE2eIntakePayloadPublisher publisher =")
        .contains("minioClient, INTAKE_EXCHANGE_BUCKET, INTAKE_EXCHANGE_PAYLOAD_PREFIX")
        .contains("return publisher.prepare()")
        .doesNotContain("MinioClient.builder", "setTimeout(", "putObject(", "makeBucket(");
    assertThat(pinsBean).contains("@Lazy(false)");
    assertThat(materializerBean)
        .contains("@Lazy(false)")
        .contains("@Qualifier(\"targetE2eIntakePayloadPublisher\")")
        .contains("TargetIntakeRuntimePins pins");
    assertThat(ingressBean)
        .contains("@Lazy(false)")
        .contains("TargetIntakeMaterializer materializer");
  }

  @Test
  void acceptsCanonicalPayloadWhoseEmbeddedHashExcludesItsOwnField() {
    var payload = JsonNodeFactory.instance.objectNode();
    payload.put("schema_version", "intake-domain-snapshot.v2");
    payload.put("snapshot_id", "snapshot-1");
    payload.put("snapshot_hash", "0".repeat(64));
    String hash = IntakeContractHashes.canonicalHashExcluding(payload, "snapshot_hash");
    payload.put("snapshot_hash", hash);

    assertThatCode(() -> MinioTargetE2eIntakePayloadPublisher.requireCanonicalSelfHash(
            ContractJson.canonicalize(payload), "snapshot_hash", hash))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsAHashThatDoesNotBindTheCanonicalPayload() {
    var payload = JsonNodeFactory.instance.objectNode();
    payload.put("schema_version", "intake-turn-event.v2");
    payload.put("event_hash", "a".repeat(64));

    assertThatThrownBy(() -> MinioTargetE2eIntakePayloadPublisher.requireCanonicalSelfHash(
            ContractJson.canonicalize(payload), "event_hash", "a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("target Intake payload hash is invalid");
  }

  @Test
  void acceptsExactCanonicalBranchPayloadBoundByItsContentHash() {
    IntakeBranchCommand branch =
        new IntakeBranchCommand(
            IntakeBranchCommand.SCHEMA_VERSION,
            "intake-branch:command-1",
            CommandType.INTAKE_CONFIRM,
            Party.INITIATOR,
            IntakeBranchCommand.Operation.INITIATOR_ACCEPT,
            true,
            "MISSING_DELIVERY",
            RiskLevel.MEDIUM,
            null,
            null);
    ObjectMapper snakeCaseMapper = new ObjectMapper();
    snakeCaseMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    var document = snakeCaseMapper.valueToTree(branch);
    byte[] canonical = ContractJson.canonicalize(document);

    assertThatCode(
            () ->
                MinioTargetE2eIntakePayloadPublisher.requireCanonicalBranchHash(
                    canonical, ContractJson.sha256Hex(document)))
        .doesNotThrowAnyException();
  }

  private static String beanMethod(String source, String methodName) {
    int method = source.indexOf(methodName + "(");
    if (method < 0) {
      throw new AssertionError("missing bean method " + methodName);
    }
    int start = source.lastIndexOf("  @Bean", method);
    int end = source.indexOf("\n  @Bean", method);
    return source.substring(start, end < 0 ? source.length() : end);
  }
}
