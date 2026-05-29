package com.example.teacherassistantai.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "document_nodes", indexes = {
        @Index(name = "idx_document_node_document", columnList = "document_id"),
        @Index(name = "idx_document_node_parent", columnList = "parent_id"),
        @Index(name = "idx_document_node_subject", columnList = "subject_id"),
        @Index(name = "idx_document_node_doc_order", columnList = "document_id, order_index"),
        @Index(name = "idx_document_nodes_doc_parent_order", columnList = "document_id, parent_id, order_index")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_document_nodes_document_node_key", columnNames = {"document_id", "node_key"})
})
public class DocumentNode extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    Document document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    DocumentNode parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    @Builder.Default
    List<DocumentNode> children = new ArrayList<>();

    @Column(name = "subject_id", nullable = false)
    Long subjectId;

    @Column(name = "node_key", length = 100, nullable = false)
    String nodeKey;

    @Column(name = "node_type", length = 30, nullable = false)
    String nodeType;

    @Column(nullable = false)
    Integer level;

    @Column(length = 500)
    String title;

    @Column(name = "section_path", columnDefinition = "TEXT")
    String sectionPath;

    @Column(name = "order_index", nullable = false)
    Integer orderIndex;

    @Column(name = "page_from")
    Integer pageFrom;

    @Column(name = "page_to")
    Integer pageTo;

    @Column(columnDefinition = "TEXT")
    String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_jsonb", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    Map<String, Object> metadataJsonb = new HashMap<>();
}
