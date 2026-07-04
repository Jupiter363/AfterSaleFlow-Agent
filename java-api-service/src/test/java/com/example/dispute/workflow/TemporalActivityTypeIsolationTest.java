package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.temporal.FulfillmentDisputeActivities;
import io.temporal.activity.ActivityInterface;
import org.junit.jupiter.api.Test;

class TemporalActivityTypeIsolationTest {

    @Test
    void finalFulfillmentActivitiesUseANamespaceDistinctFromLegacyActivities() {
        ActivityInterface contract =
                FulfillmentDisputeActivities.class.getAnnotation(ActivityInterface.class);

        assertThat(contract.namePrefix()).isEqualTo("FinalFulfillment_");
    }
}
