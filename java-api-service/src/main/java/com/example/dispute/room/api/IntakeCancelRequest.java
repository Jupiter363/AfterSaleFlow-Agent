package com.example.dispute.room.api;

import jakarta.validation.constraints.Size;

public record IntakeCancelRequest(
        @Size(max = 1000) String reason) {}
