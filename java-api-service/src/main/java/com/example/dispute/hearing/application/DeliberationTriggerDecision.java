package com.example.dispute.hearing.application;

import java.util.List;

public record DeliberationTriggerDecision(
        boolean shouldDeliberate, List<String> reasons) {}
