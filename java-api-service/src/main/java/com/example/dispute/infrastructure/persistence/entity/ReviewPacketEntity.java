package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "review_packet")
public class ReviewPacketEntity extends AbstractEntity {
    protected ReviewPacketEntity() {}
}
