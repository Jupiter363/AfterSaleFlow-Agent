package com.example.dispute.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

public final class FlywayMigrationMain {

    private FlywayMigrationMain() {}

    public static void main(String[] args) {
        Flyway flyway =
                Flyway.configure()
                        .dataSource(
                                "jdbc:postgresql://%s:%s/%s"
                                        .formatted(
                                                requiredEnvironment("POSTGRES_HOST"),
                                                environmentOrDefault(
                                                        "POSTGRES_PORT", "5432"),
                                                requiredEnvironment("JAVA_DB_NAME")),
                                requiredEnvironment("POSTGRES_USER"),
                                requiredEnvironment("POSTGRES_PASSWORD"))
                        .locations("classpath:db/migration")
                        .load();
        MigrateResult result = flyway.migrate();
        String currentVersion =
                flyway.info().current() == null
                        ? "none"
                        : flyway.info().current().getVersion().getVersion();

        System.out.printf(
                "Flyway migration complete: version=%s, migrations=%d%n",
                currentVersion, result.migrationsExecuted);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be configured");
        }
        return value;
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
