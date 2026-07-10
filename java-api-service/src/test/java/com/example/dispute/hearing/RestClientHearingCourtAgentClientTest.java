package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.dispute.hearing.application.HearingCourtAgentCommand;
import com.example.dispute.workflow.infrastructure.RestClientHearingCourtAgentClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientHearingCourtAgentClientTest {

    @Test
    void postsRoundTurnToPythonAgentAndValidatesJudgeResult() {
        RestClient.Builder builder =
                RestClient.builder()
                        .baseUrl("http://agent.test")
                        .defaultHeader("X-Service-Secret", "secret");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://agent.test/internal/agents/hearing/round-turn"))
                .andExpect(header("X-Service-Secret", "secret"))
                .andExpect(header("X-Trace-Id", "TRACE_round"))
                .andExpect(header("X-Request-Id", "REQ_round"))
                .andExpect(jsonPath("$.case_id").value("CASE_COURT"))
                .andExpect(jsonPath("$.round_no").value(1))
                .andExpect(jsonPath("$.courtroom_context.intake_dossier.case_story")
                        .value("用户称物流显示已签收但本人未收到包裹。"))
                .andExpect(jsonPath("$.courtroom_context.evidence_dossier.fact_evidence_matrix[0].fact")
                        .value("物流显示已签收"))
                .andExpect(jsonPath("$.courtroom_context.evidence_dossier_ref.baseline_version")
                        .value(1))
                .andExpect(jsonPath("$.courtroom_context.evidence_dossier_ref.active_version")
                        .value(2))
                .andExpect(jsonPath("$.courtroom_context.evidence_dossier.dossier_version")
                        .value(2))
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "speaker_role":"JUDGE",
                                  "message_text":"第一轮事实陈述已封存。请进入第二轮定向说明。",
                                  "round_summary":"双方围绕签收未收到进行第一轮陈述。",
                                  "questions_for_user":["请补充签收现场情况。"],
                                  "questions_for_merchant":["请补充物流交接记录。"],
                                  "court_event_type":"JUDGE_NEXT_QUESTIONS_READY",
                                  "round_no":1,
                                  "next_round_no":2,
                                  "final_draft_required":false,
                                  "review_focus_signal":["用户关注签收人身份核验。"],
                                  "prompt_version":"hearing-round-turn-v1",
                                  "model":"deepseek-v4-flash"
                                }
                                """,
                                MediaType.APPLICATION_JSON));
        var client = new RestClientHearingCourtAgentClient(builder.build());

        var result =
                client.generateRoundTurn(
                        new HearingCourtAgentCommand(
                                "CASE_COURT",
                                "WORKFLOW_COURT",
                                "ORDER_COURT",
                                "AS_COURT",
                                "LOG_COURT",
                                "SIGNED_NOT_RECEIVED",
                                "签收未收到",
                                "用户称签收未收到，商家称已履约发货。",
                                "HIGH",
                                1,
                                2,
                                false,
                                "COMPLETED",
                                null,
                                "{\"trigger\":\"BOTH_PARTIES_SUBMITTED\"}",
                                """
                                {
                                  "intake_dossier": {
                                    "case_story": "用户称物流显示已签收但本人未收到包裹。"
                                  },
                                  "evidence_dossier": {
                                    "dossier_version": 2,
                                    "fact_evidence_matrix": [
                                      {
                                        "fact": "物流显示已签收",
                                        "supporting_evidence": ["EVIDENCE_LOGISTICS"]
                                      }
                                    ]
                                  },
                                  "evidence_dossier_ref": {
                                    "baseline_version": 1,
                                    "active_version": 2
                                  }
                                }
                                """,
                                List.of(
                                        new HearingCourtAgentCommand.PartySubmission(
                                                "USER",
                                                "user-local",
                                                "PARTY_ACTION",
                                                "{\"statement\":\"用户陈述\"}"))),
                        "TRACE_round",
                        "REQ_round");

        assertThat(result.speakerRole()).isEqualTo("JUDGE");
        assertThat(result.messageText()).contains("第一轮事实陈述已封存");
        assertThat(result.courtEventType()).isEqualTo("JUDGE_NEXT_QUESTIONS_READY");
        assertThat(result.nextRoundNo()).isEqualTo(2);
        assertThat(result.finalDraftRequired()).isFalse();
        assertThat(result.reviewFocusSignal()).containsExactly("用户关注签收人身份核验。");
        server.verify();
    }
}
