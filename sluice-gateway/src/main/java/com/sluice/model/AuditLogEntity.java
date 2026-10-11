package com.sluice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant at;

    @Column(nullable = false, length = 128)
    private String actor;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(length = 256)
    private String target;

    @Column(columnDefinition = "text")
    private String detail;

    protected AuditLogEntity() {
    }

    public AuditLogEntity(Instant at, String actor, String action, String target, String detail) {
        this.at = at;
        this.actor = actor;
        this.action = action;
        this.target = target;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public Instant getAt() {
        return at;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getTarget() {
        return target;
    }

    public String getDetail() {
        return detail;
    }
}
