package com.example.dispute.casecore.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.config.ActorRole;
import com.example.dispute.room.application.IntakeLobbySeed;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CreateDisputeRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void validatesStructuredClaimResolutionSeedWithoutTreatingItAsAnExecutionAction() {
        CreateDisputeRequest request =
                requestWithClaimSeed(
                        new IntakeLobbySeed.ClaimResolutionSeed(
                                "USER",
                                "REFUND_NOW",
                                new BigDecimal("-1"),
                                "x".repeat(513),
                                "用户希望退款。",
                                "我希望退款。"));

        var violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(
                        "claimResolutionSeed.requestedResolution",
                        "claimResolutionSeed.requestedAmount",
                        "claimResolutionSeed.requestedItems");
    }

    @Test
    void acceptsAllowedStructuredClaimResolutionSeed() {
        CreateDisputeRequest request =
                requestWithClaimSeed(
                        new IntakeLobbySeed.ClaimResolutionSeed(
                                "USER",
                                "REFUND",
                                new BigDecimal("299.00"),
                                "儿童手表 1 件",
                                "物流显示签收但用户本人没有收到包裹，希望退款。",
                                "我没收到包裹，希望退款"));

        assertThat(validator.validate(request)).isEmpty();
    }

    private static CreateDisputeRequest requestWithClaimSeed(
            IntakeLobbySeed.ClaimResolutionSeed claimResolutionSeed) {
        return new CreateDisputeRequest(
                ActorRole.USER,
                "ORDER-CLAIM-1",
                null,
                null,
                "user-1",
                "merchant-1",
                "我没收到包裹，希望退款",
                claimResolutionSeed,
                null,
                List.of(),
                "WEB");
    }
}
