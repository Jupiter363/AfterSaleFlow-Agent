package com.example.dispute.workflow.targete2e.temporal.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class JdbcTargetIntakePartyScopeSourceTest {

  private static final String ACTIVATION_ID = "p9act.v1." + "a".repeat(32);
  private static final String MANIFEST_HASH = "b".repeat(64);
  private static final TargetIntakePartyScopeSource.Request REQUEST =
      new TargetIntakePartyScopeSource.Request("tenant-1", "case-1", 2, 17);

  @Test
  void resolvesUserInitiatedAuthorityWithExactTargetRoomBinding() {
    NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
        .thenReturn(List.of(row("user-1", ActorRole.USER, "merchant-1", ActorRole.MERCHANT)));

    var resolved = new JdbcTargetIntakePartyScopeSource(jdbc).resolve(REQUEST);

    assertThat(resolved.initiator().party()).isEqualTo(IntakeParty.INITIATOR);
    assertThat(resolved.initiator().actorId()).isEqualTo("user-1");
    assertThat(resolved.respondent().actorId()).isEqualTo("merchant-1");
    assertThat(resolved.initiator().actorScopeHash())
        .isEqualTo(TargetIntakeActorScopes.hash("case-1", "user-1", ActorRole.USER));
    assertExactRouteQuery(jdbc);
  }

  @Test
  void resolvesMerchantInitiatedAuthorityWithoutTreatingUserAsInitiator() {
    NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
        .thenReturn(List.of(row("merchant-1", ActorRole.MERCHANT, "user-1", ActorRole.USER)));

    var resolved = new JdbcTargetIntakePartyScopeSource(jdbc).resolve(REQUEST);

    assertThat(resolved.initiator().actorId()).isEqualTo("merchant-1");
    assertThat(resolved.initiator().actorRole()).isEqualTo(ActorRole.MERCHANT);
    assertThat(resolved.actor("user-1", ActorRole.USER).party())
        .isEqualTo(IntakeParty.RESPONDENT);
    assertThat(resolved.respondent().actorScopeHash())
        .isEqualTo(TargetIntakeActorScopes.hash("case-1", "user-1", ActorRole.USER));
  }

  @Test
  void rejectsPartyRowsThatDoNotMatchUserAndMerchantIdentityColumns() {
    NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
        .thenReturn(
            List.of(
                Map.of(
                    "activation_id", ACTIVATION_ID,
                    "activation_manifest_hash", MANIFEST_HASH,
                    "user_id", "user-1",
                    "merchant_id", "merchant-1",
                    "initiator_id", "user-1",
                    "initiator_role", "MERCHANT",
                    "respondent_id", "merchant-1",
                    "respondent_role", "USER")));

    assertThatThrownBy(() -> new JdbcTargetIntakePartyScopeSource(jdbc).resolve(REQUEST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("distinct USER/MERCHANT assignment");
  }

  @Test
  void rejectsTamperedActorScopeHashInActivityResult() {
    var valid =
        TargetIntakePartyScopeSource.ResolvedPartyScopes.create(
            ACTIVATION_ID,
            MANIFEST_HASH,
            REQUEST,
            "merchant-1",
            ActorRole.MERCHANT,
            "user-1",
            ActorRole.USER);

    assertThatThrownBy(
            () ->
                new TargetIntakePartyScopeSource.ResolvedPartyScopes(
                    valid.schemaVersion(),
                    valid.activationId(),
                    valid.activationManifestHash(),
                    valid.tenantSurrogate(),
                    valid.caseId(),
                    valid.roomEpoch(),
                    valid.roomFencingToken(),
                    new TargetIntakePartyScopeSource.PartyBinding(
                        IntakeParty.INITIATOR,
                        "merchant-1",
                        ActorRole.MERCHANT,
                        "c".repeat(64)),
                    valid.respondent()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not canonical");
  }

  private static Map<String, Object> row(
      String initiatorId,
      ActorRole initiatorRole,
      String respondentId,
      ActorRole respondentRole) {
    return Map.of(
        "activation_id", ACTIVATION_ID,
        "activation_manifest_hash", MANIFEST_HASH,
        "user_id", "user-1",
        "merchant_id", "merchant-1",
        "initiator_id", initiatorId,
        "initiator_role", initiatorRole.name(),
        "respondent_id", respondentId,
        "respondent_role", respondentRole.name());
  }

  private static void assertExactRouteQuery(NamedParameterJdbcOperations jdbc) {
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc).queryForList(sql.capture(), parameters.capture());
    assertThat(sql.getValue())
        .contains(
            "target_e2e_room_epoch_binding",
            "target_e2e_activation",
            "target_e2e_case_reservation",
            "fulfillment_dispute_case",
            "epoch.room_type = 'INTAKE'",
            "epoch.fencing_token = :roomFencingToken",
            "epoch.lifecycle_status = 'PROVISIONING'",
            "epoch.provisioning_status = 'PROVISIONING'",
            "epoch.room_temporal_run_id is null",
            "epoch.lifecycle_status = 'ACTIVE'",
            "epoch.provisioning_status = 'READY'",
            "coalesce(btrim(epoch.room_temporal_run_id), '') <> ''",
            "binding.execution_lane = 'TARGET_E2E_CANDIDATE'");
    assertThat(parameters.getValue().getValue("tenantSurrogate")).isEqualTo("tenant-1");
    assertThat(parameters.getValue().getValue("caseId")).isEqualTo("case-1");
    assertThat(parameters.getValue().getValue("roomEpoch")).isEqualTo(2L);
    assertThat(parameters.getValue().getValue("roomFencingToken")).isEqualTo(17L);
  }
}
