package com.bupt.cqy.analyzer.config;

import com.bupt.cqy.analyzer.service.AuditAssistant;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class DeepSeekConfig {

    @Value("${deepseek.api.key}")
    private String apiKey;

    /**
     * 普通（非流式）模型 — 用于审计报告生成（AuditAssistant）
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public OpenAiChatModel deepSeekChatModel() {
        return OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(apiKey)
                .modelName("deepseek-reasoner")
                .temperature(0.3)
                .maxTokens(65536)
                .timeout(Duration.ofSeconds(300))
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /**
     * V3（非流式）模型 — 用于支持底层 Function Calling (Agent 工具调用)
     */
    @Bean
    public OpenAiChatModel deepSeekV3ChatModel() {
        return OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(apiKey)
                .modelName("deepseek-chat")
                .temperature(0.3)
                .maxTokens(8192)
                .timeout(Duration.ofSeconds(300))
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    /**
     * 流式模型 — 用于 RAG 对话（RagChatService SSE）。
     * 同样使用 deepseek-reasoner，但通过 StreamingChatLanguageModel 接口
     * 逐 token 推送，大幅降低用户感知延迟。
     */
    @Bean
    public OpenAiStreamingChatModel deepSeekStreamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(apiKey)
                .modelName("deepseek-reasoner")
                .temperature(0.3)
                .maxTokens(65536)
                .timeout(Duration.ofSeconds(300))
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    @Bean
    public AuditAssistant auditAssistant(OpenAiChatModel deepSeekChatModel) {
        return AiServices.builder(AuditAssistant.class)
                .chatLanguageModel(deepSeekChatModel)
                .build();
    }
}
