package com.example.dispute.workflow.formalsinkarchitecturefixture;

final class CrossFileFormalWrapper {

    private final CrossFileFormalDelegate delegate;

    CrossFileFormalWrapper(CrossFileFormalDelegate delegate) {
        this.delegate = delegate;
    }

    CrossFileFormalDelegate delegate() {
        return delegate;
    }
}
