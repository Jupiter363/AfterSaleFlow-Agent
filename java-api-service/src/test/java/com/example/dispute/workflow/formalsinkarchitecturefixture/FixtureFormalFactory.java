package com.example.dispute.workflow.formalsinkarchitecturefixture;

import com.example.dispute.workflow.application.intake.IntakeFormalCommitPort;

public final class FixtureFormalFactory {

    public static final Object FORMAL_ACTIVITY = new Object();

    private static IntakeFormalCommitPort formalCommitPort;

    private FixtureFormalFactory() {}

    public static Object formalActivity() {
        return FORMAL_ACTIVITY;
    }

    public static final class Nested {

        private static IntakeFormalCommitPort nestedFormalCommitPort;

        private Nested() {}

        public static Object nestedFormalActivity() {
            return nestedFormalCommitPort;
        }
    }
}
