package com.example.teacherassistantai.service.quiz;

import com.example.teacherassistantai.entity.DocumentNode;
import org.springframework.stereotype.Component;

@Component
public class QuizGenerationStrategy {

    public enum QuizInputType { RAW_CHUNKS, CHILD_SUMMARIES }

    static final int CHUNK_THRESHOLD = 75;

    public QuizInputType determine(DocumentNode node, int scopeChunkCount) {
        return scopeChunkCount < CHUNK_THRESHOLD ? QuizInputType.RAW_CHUNKS : QuizInputType.CHILD_SUMMARIES;
    }
}
