package com.example.dispute.review.domain;

public final class ReviewDecisionConflictException extends RuntimeException {
    public ReviewDecisionConflictException(String message) {
        super(message);
    }
}
