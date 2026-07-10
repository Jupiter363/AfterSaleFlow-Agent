package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.hearing.application.HearingFinalDraftService;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class HearingFinalDraftServiceContractTest {

    @Test
    void finalDraftServiceCanOnlyAdoptATemporalDraftAndCannotGenerateOne() {
        assertThat(
                        Arrays.stream(HearingFinalDraftService.class.getDeclaredMethods())
                                .map(method -> method.getName()))
                .contains("adoptExistingDraftForFinalSealedRound")
                .doesNotContain("ensureDraftForFinalSealedRound");
        assertThat(
                        Arrays.stream(HearingFinalDraftService.class.getDeclaredFields())
                                .map(field -> field.getType().getName()))
                .doesNotContain(
                        "com.example.dispute.workflow.application.HearingAgentClient");
    }
}
