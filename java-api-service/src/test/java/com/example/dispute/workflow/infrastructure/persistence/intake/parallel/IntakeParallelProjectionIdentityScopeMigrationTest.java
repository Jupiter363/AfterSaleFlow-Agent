package com.example.dispute.workflow.infrastructure.persistence.intake.parallel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class IntakeParallelProjectionIdentityScopeMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src",
            "main",
            "resources",
            "db",
            "migration",
            "V090__intake_parallel_projection_identity_scope.sql");

    @Test
    void scopesProviderProjectionIdentityToOneFrameGeneration() throws Exception {
        String sql = Files.readString(MIGRATION)
                .replace("\r\n", "\n")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("drop constraint uq_intake_parallel_projection_item_id")
                .contains("unique (frame_set_id, frame_type, frame_generation, canonical_item_id)")
                .doesNotContain("unique (frame_set_id, canonical_item_id)");
    }
}
