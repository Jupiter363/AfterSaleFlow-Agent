package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.IssueEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IssueRepository extends JpaRepository<IssueEntity, String> {}
