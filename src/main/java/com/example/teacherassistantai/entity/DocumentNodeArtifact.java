package com.example.teacherassistantai.entity;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "document_node_artifacts", indexes = {
        @Index(name = "idx_node_artifacts_document_type_status", columnList = "document_id, artifact_type, status"),
        @Index(name = "idx_node_artifacts_node_type", columnList = "document_node_id, artifact_type")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_node_artifact_version",
                columnNames = {"document_node_id", "artifact_type", "prompt_version", "model", "source_hash"})
})
public class DocumentNodeArtifact extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    Document document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_node_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    DocumentNode documentNode;

    @Enumerated(EnumType.STRING)
    @Column(name = "artifact_type", length = 40, nullable = false)
    DocumentNodeArtifactType artifactType;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    DocumentNodeArtifactStatus status;

    @Column(name = "prompt_version", length = 80, nullable = false)
    String promptVersion;

    @Column(length = 120, nullable = false)
    String model;

    @Column(name = "source_hash", length = 128, nullable = false)
    String sourceHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_jsonb", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    Map<String, Object> contentJsonb = new HashMap<>();

    @Column(name = "error_message", columnDefinition = "TEXT")
    String errorMessage;

    @Column(name = "token_count")
    Integer tokenCount;
}
