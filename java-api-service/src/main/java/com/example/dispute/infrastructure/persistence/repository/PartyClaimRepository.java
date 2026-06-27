package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.PartyClaimEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartyClaimRepository extends JpaRepository<PartyClaimEntity, String> {}
