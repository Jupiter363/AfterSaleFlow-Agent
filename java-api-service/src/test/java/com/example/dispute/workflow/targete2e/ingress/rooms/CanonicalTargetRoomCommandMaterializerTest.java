package com.example.dispute.workflow.targete2e.ingress.rooms;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import org.junit.jupiter.api.Test;

class CanonicalTargetRoomCommandMaterializerTest {

  @Test
  void partyCompletionIsMaterializedButIsNotAGraphCommand() {
    assertThat(CanonicalTargetRoomCommandMaterializer.isMaterializedCommand(
        CommandType.PARTY_EVIDENCE_COMPLETE)).isTrue();
  }
}
