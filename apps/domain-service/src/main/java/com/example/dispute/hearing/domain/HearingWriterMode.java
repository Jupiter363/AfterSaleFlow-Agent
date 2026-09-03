package com.example.dispute.hearing.domain;

/** Immutable writer selected by the shared case_room_epoch authority. */
public enum HearingWriterMode {
    LEGACY,
    SHADOW,
    TEMPORAL
}
