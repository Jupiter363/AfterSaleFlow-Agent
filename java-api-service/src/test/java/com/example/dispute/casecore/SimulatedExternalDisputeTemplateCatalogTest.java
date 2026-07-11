package com.example.dispute.casecore;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.casecore.application.SimulatedExternalDisputeTemplate;
import com.example.dispute.casecore.application.SimulatedExternalDisputeTemplateCatalog;
import com.example.dispute.config.ActorRole;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SimulatedExternalDisputeTemplateCatalogTest {

    private final SimulatedExternalDisputeTemplateCatalog catalog =
            new SimulatedExternalDisputeTemplateCatalog();

    @Test
    void containsExactlyTwentyOrderedUniqueAndCompleteUtf8Templates() {
        assertThat(SimulatedExternalDisputeTemplateCatalog.SOURCE_SYSTEM)
                .isEqualTo("TEMPLATE_SIMULATED_OMS");
        assertThat(catalog.all()).hasSize(20);
        assertThat(catalog.all())
                .extracting(SimulatedExternalDisputeTemplate::templateNo)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 20).boxed().toList());
        assertThat(catalog.all())
                .extracting(SimulatedExternalDisputeTemplate::title)
                .doesNotHaveDuplicates()
                .allSatisfy(title -> assertThat(title).containsPattern("[\\p{IsHan}]").isNotBlank());
        assertThat(catalog.all())
                .extracting(SimulatedExternalDisputeTemplate::disputeType)
                .doesNotHaveDuplicates();
        assertThat(catalog.all())
                .allSatisfy(
                        template -> {
                            assertThat(template.description()).isNotBlank();
                            assertThat(template.originalStatement())
                                    .isNotBlank()
                                    .isNotEqualTo(template.description())
                                    .contains("我")
                                    .containsAnyOf("商家", "客服", "对方");
                            assertThat(template.riskLevel()).isNotNull();
                            assertThat(template.requestedResolution())
                                    .isIn(
                                            "REFUND",
                                            "RETURN_REFUND",
                                            "RESHIP",
                                            "REPLACE_OR_REPAIR",
                                            "COMPENSATION",
                                            "CANCEL_ORDER",
                                            "VERIFY_OR_EXPLAIN_ONLY",
                                            "OTHER");
                            assertThat(template.requestedItems()).isNotBlank();
                            assertThat(template.requestReason()).isNotBlank();
                            assertThat(template.respondentAttitude())
                                    .isIn(
                                            "NOT_RESPONDED",
                                            "AGREE",
                                            "PARTIALLY_AGREE",
                                            "DISAGREE",
                                            "ALTERNATIVE_PROPOSED",
                                            "NEED_MORE_INFO",
                                            "PLATFORM_UNKNOWN");
                            assertThat(template.respondentPosition()).isNotBlank();

                            SimulatedExternalDisputeTemplate.InitiatorPerspective user =
                                    template.forInitiator(ActorRole.USER);
                            assertThat(user.originalStatement())
                                    .isEqualTo(template.originalStatement());
                            assertThat(user.requestedResolution())
                                    .isEqualTo(template.requestedResolution());
                            assertThat(user.respondentPosition())
                                    .isEqualTo(template.respondentPosition());

                            SimulatedExternalDisputeTemplate.InitiatorPerspective merchant =
                                    template.forInitiator(ActorRole.MERCHANT);
                            assertThat(merchant.originalStatement())
                                    .startsWith("我们")
                                    .contains("用户的诉求是：")
                                    .contains(template.requestReason())
                                    .isNotEqualTo(template.originalStatement());
                            assertThat(merchant.requestReason())
                                    .startsWith("我们")
                                    .contains("希望平台核验");
                            assertThat(merchant.respondentPosition())
                                    .isEqualTo("对方主张：" + template.requestReason())
                                    .doesNotContain(template.respondentPosition());
                            assertThat(merchant.requestedResolution())
                                    .isIn(
                                            template.requestedResolution(),
                                            "VERIFY_OR_EXPLAIN_ONLY");
                        });
    }
}
