package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DocumentOutlineSummaryRenderer {

    private final DocumentNodeRepository documentNodeRepository;

    public Optional<String> render(DocumentNode node) {
        if (node == null || node.getId() == null) {
            return Optional.empty();
        }
        if ("part".equals(node.getNodeType())) {
            return Optional.of(renderPart(node));
        }
        if ("document".equals(node.getNodeType())) {
            return Optional.of(renderDocument(node));
        }
        return Optional.empty();
    }

    private String renderPart(DocumentNode partNode) {
        List<DocumentNode> children = documentNodeRepository.findByParentIdOrderByOrderIndexAsc(partNode.getId());
        List<DocumentNode> chapters = children.stream()
                .filter(child -> "chapter".equals(child.getNodeType()))
                .toList();
        if (chapters.isEmpty()) {
            chapters = children;
        }

        StringBuilder answer = new StringBuilder();
        answer.append("Cấu trúc ").append(displayPath(partNode)).append(":\n\n");
        answer.append("Phần này gồm các chương/nội dung chính sau:");
        appendNodeList(answer, chapters, "");
        return answer.toString();
    }

    private String renderDocument(DocumentNode documentNode) {
        List<DocumentNode> topLevelNodes = documentNodeRepository.findByParentIdOrderByOrderIndexAsc(documentNode.getId());
        StringBuilder answer = new StringBuilder();
        answer.append("Cấu trúc ").append(displayPath(documentNode)).append(":\n\n");
        answer.append("Tài liệu gồm các phần/chương chính sau:");
        for (DocumentNode topLevelNode : topLevelNodes) {
            if ("part".equals(topLevelNode.getNodeType())) {
                answer.append("\n- ").append(title(topLevelNode));
                List<DocumentNode> chapters = documentNodeRepository.findByParentIdOrderByOrderIndexAsc(topLevelNode.getId())
                        .stream()
                        .filter(child -> "chapter".equals(child.getNodeType()))
                        .toList();
                appendNodeList(answer, chapters, "  ");
            } else if ("chapter".equals(topLevelNode.getNodeType())) {
                answer.append("\n- ").append(title(topLevelNode));
            }
        }
        return answer.toString();
    }

    private void appendNodeList(StringBuilder answer, List<DocumentNode> nodes, String indent) {
        if (nodes == null || nodes.isEmpty()) {
            answer.append("\n").append(indent).append("- Chưa có danh sách mục con rõ ràng.");
            return;
        }
        for (DocumentNode node : nodes) {
            answer.append("\n").append(indent).append("- ").append(title(node));
        }
    }

    private String displayPath(DocumentNode node) {
        String path = node.getSectionPath();
        if (path != null && !path.isBlank()) {
            return path;
        }
        return title(node);
    }

    private String title(DocumentNode node) {
        String title = node.getTitle();
        return title == null || title.isBlank() ? "node " + node.getId() : title;
    }
}
