package com.example.dispute.casecore;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.casecore.application.SimulatedExternalDisputeTemplate;
import com.example.dispute.casecore.application.SimulatedExternalDisputeTemplateCatalog;
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
                        });
    }
}
