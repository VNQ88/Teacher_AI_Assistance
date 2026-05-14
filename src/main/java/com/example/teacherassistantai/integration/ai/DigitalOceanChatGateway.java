package com.example.teacherassistantai.integration.ai;

import com.example.teacherassistantai.exception.AiRateLimitedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DigitalOceanChatGateway implements AiChatGateway {

    private final ChatClient chatClient;
    private final AiModelRoutingService routingService;
    private final DigitalOceanEnrichmentChatClient enrichmentChatClient;
    private final DigitalOceanAiRateLimiter rateLimiter;

    public DigitalOceanChatGateway(@Qualifier("ragChatClient") ChatClient chatClient,
                                   AiModelRoutingService routingService,
                                   DigitalOceanEnrichmentChatClient enrichmentChatClient,
                                   DigitalOceanAiRateLimiter rateLimiter) {
        this.chatClient = chatClient;
        this.routingService = routingService;
        this.enrichmentChatClient = enrichmentChatClient;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public String generateAnswer(String prompt, Double temperature, AiWorkload workload) {
        return generate(prompt, temperature, workload).content();
    }

    @Override
    public AiChatCompletion generate(String prompt, Double temperature, AiWorkload workload) {
        if (prompt == null || prompt.isBlank()) {
            AiWorkload normalized = workload == null ? AiWorkload.RAG_CHAT : workload.normalized();
            return new AiChatCompletion("Khong co cau hoi hop le.", null, null, null, normalized);
        }

        AiModelRoute route = routingService.route(workload == null ? AiWorkload.RAG_CHAT : workload);
        rateLimiter.acquire(route.workload(), route.accountAlias(), route.model(), null);

        try {
            if (route.enrichment()) {
                AiChatCompletion completion = enrichmentChatClient.complete(prompt, temperature, route);
                rateLimiter.recordSuccess(route.workload(), route.accountAlias(), route.model(),
                        completion.rateLimit(), completion.usage());
                return completion;
            }

            String content = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            return new AiChatCompletion(
                    content != null ? content : "Xin loi, he thong tam thoi chua tao duoc cau tra loi.",
                    null,
                    null,
                    route.model(),
                    route.workload()
            );
        } catch (DigitalOceanEnrichmentRateLimitException ex) {
            log.warn("429 received workload={} accountAlias={} model={}", route.workload(), route.accountAlias(), route.model());
            rateLimiter.handle429(route.workload(), route.accountAlias(), route.model(), ex.snapshot());
            throw ex;
        } catch (Exception ex) {
            if (is429(ex)) {
                log.warn("429 received workload={}", workload);
                if (route.workload().enrichment()) {
                    rateLimiter.handle429(route.workload(), route.accountAlias(), route.model(), null);
                }
                throw new AiRateLimitedException("AI provider rate limit exceeded. Please retry later.");
            }
            throw ex;
        }
    }

    private boolean is429(Exception ex) {
        String msg = ex.getMessage();
        return msg != null && msg.contains("429");
    }
}
