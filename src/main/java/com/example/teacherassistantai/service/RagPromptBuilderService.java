package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.ChatMessage;
import com.example.teacherassistantai.entity.DocumentChunk;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagPromptBuilderService {

    public String buildPrompt(String question, List<ChatMessage> history, List<DocumentChunk> contextChunks) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Ban la tro giang hoc tap. Chi tra loi dua tren context duoc cung cap.\\n\\n");
        prompt.append("History:\\n");
        for (ChatMessage message : history) {
            prompt.append("- ").append(message.getRole()).append(": ").append(message.getContent()).append("\\n");
        }

        prompt.append("\\nContext:\\n");
        for (DocumentChunk chunk : contextChunks) {
            prompt.append("[Chunk ").append(chunk.getId()).append("] ").append(chunk.getContent()).append("\\n");
        }

        prompt.append("\\nQuestion: ").append(question).append("\\n");
        prompt.append("Tra loi ngan gon, co dan chung theo chunk id neu co.\\n");
        return prompt.toString();
    }
}

