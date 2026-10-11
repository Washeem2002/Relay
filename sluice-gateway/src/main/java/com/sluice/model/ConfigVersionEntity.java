package com.sluice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "config_versions")
public class ConfigVersionEntity {

    @Id
    private Long version;

    @Column(nullable = false, columnDefinition = "text")
    private String document;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 128)
    private String author;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(length = 512)
    private String comment;

    protected ConfigVersionEntity() {
    }

    public ConfigVersionEntity(Long version, String document, Instant createdAt, String author, String action,
                               String comment) {
        this.version = version;
        this.document = document;
        this.createdAt = createdAt;
        this.author = author;
        this.action = action;
        this.comment = comment;
    }

    public Long getVersion() {
        return version;
    }

    public String getDocument() {
        return document;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getAuthor() {
        return author;
    }

    public String getAction() {
        return action;
    }

    public String getComment() {
        return comment;
    }
}
