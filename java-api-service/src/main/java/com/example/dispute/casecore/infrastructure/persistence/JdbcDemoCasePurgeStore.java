package com.example.dispute.casecore.infrastructure.persistence;

import com.example.dispute.casecore.application.DemoCasePurgeStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDemoCasePurgeStore implements DemoCasePurgeStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcDemoCasePurgeStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void purge(String caseId, String reviewerId, String reviewerRole) {
        jdbcTemplate.queryForObject(
                "select purge_simulated_dispute_case(?, ?, ?)",
                String.class,
                caseId,
                reviewerId,
                reviewerRole);
    }
}
