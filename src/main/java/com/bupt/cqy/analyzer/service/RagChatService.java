package com.bupt.cqy.analyzer.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatService {

    private static final int VECTOR_TOP_K = 5;
    private static final int KEYWORD_TOP_K = 3;
    private static final double MIN_SCORE = 0.3;

    private static final String SYSTEM_PROMPT = """
            你是一位拥有 20 年经验的资深架构师，正在基于已有的项目审计报告和代码片段回答开发者的问题。

            请严格基于以下检索到的上下文信息回答问题。如果上下文中没有相关信息，请坦诚说明。
            回答应当准确、专业、有深度，并尽可能引用具体的代码位置或审计结论。
            回答中请使用 Markdown 格式，便于阅读。
            """;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ChatLanguageModel chatModel;
    private final StreamingChatLanguageModel streamingChatModel;
    private final IndexService indexService;

    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    /**
     * 构建 RAG 增强后的完整 Prompt（供流式和非流式共用）
     */
    private RagContext buildRagContext(String userQuestion) {
        // 1. 向量化用户问题
        Embedding queryEmbedding = embeddingModel.embed(userQuestion).content();

        // 2. 向量相似度检索 Top-K
        List<EmbeddingMatch<TextSegment>> vectorMatches = embeddingStore.findRelevant(queryEmbedding, VECTOR_TOP_K,
                MIN_SCORE);
        log.info("向量检索命中 {} 个片段", vectorMatches.size());

        // 3. 关键字检索
        List<TextSegment> keywordMatches = keywordSearch(userQuestion, KEYWORD_TOP_K);
        log.info("关键字检索命中 {} 个片段", keywordMatches.size());

        // 4. 合并去重（向量结果优先）
        Map<String, TextSegment> mergedMap = new LinkedHashMap<>();
        for (EmbeddingMatch<TextSegment> m : vectorMatches)
            mergedMap.putIfAbsent(m.embedded().text(), m.embedded());
        for (TextSegment seg : keywordMatches)
            mergedMap.putIfAbsent(seg.text(), seg);
        List<TextSegment> mergedSegments = new ArrayList<>(mergedMap.values());

        // 5. 构建上下文 + 来源
        String context = buildContext(mergedSegments, vectorMatches);
        List<String> sources = mergedSegments.stream()
                .map(seg -> {
                    String source = seg.metadata().getString("source");
                    if ("code".equals(source))
                        return seg.metadata().getString("path");
                    return "Project-Blueprint.md";
                })
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // 6. 增强 Prompt
        String augmentedPrompt = String.format("""
                %s

                ## 检索到的上下文信息

                %s

                ## 用户问题

                %s

                请基于以上上下文信息，给出准确、专业的回答。
                """, SYSTEM_PROMPT, context, userQuestion);

        return new RagContext(augmentedPrompt, sources);
    }

    /**
     * 流式 RAG 对话，通过 SSE 逐 token 推送。
     * 格式：先发 `sources` 事件，再逐 token 发 `token` 事件，最后发 `done` 事件。
     */
    public SseEmitter chatStream(String userQuestion) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时

        streamExecutor.submit(() -> {
            try {
                log.info("收到流式提问：{}", userQuestion);
                RagContext ctx = buildRagContext(userQuestion);

                // 先推送来源信息
                String sourcesJson = ctx.sources().stream()
                        .map(s -> "\"" + s.replace("\"", "\\\"") + "\"")
                        .collect(Collectors.joining(",", "[", "]"));
                emitter.send(SseEmitter.event().name("sources").data(sourcesJson));

                // 流式生成：每个 token 推送一次
                streamingChatModel.generate(ctx.prompt(), new StreamingResponseHandler<>() {
                    @Override
                    public void onNext(String token) {
                        try {
                            emitter.send(SseEmitter.event().name("token").data(token));
                        } catch (IOException e) {
                            log.warn("SSE 推送中断: {}", e.getMessage());
                        }
                    }

                    @Override
                    public void onComplete(Response<AiMessage> response) {
                        try {
                            emitter.send(SseEmitter.event().name("done").data(""));
                            emitter.complete();
                            log.info("流式 RAG 回答完成，引用 {} 个来源", ctx.sources().size());
                        } catch (IOException e) {
                            log.warn("SSE complete 推送失败: {}", e.getMessage());
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        log.error("流式生成出错: {}", e.getMessage(), e);
                        try {
                            emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                        } catch (IOException ex) {
                            // ignore
                        }
                        emitter.completeWithError(e);
                    }
                });

            } catch (Exception e) {
                log.error("流式 RAG 处理失败: {}", e.getMessage(), e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 非流式 RAG 对话（保留，供 /api/chat 向后兼容）
     */
    public ChatResult chat(String userQuestion) {
        log.info("收到用户提问：{}", userQuestion);
        RagContext ctx = buildRagContext(userQuestion);
        String answer = chatModel.generate(ctx.prompt());
        log.info("RAG 回答生成完毕，引用 {} 个来源", ctx.sources().size());
        return new ChatResult(answer, ctx.sources());
    }

    /**
     * 关键字检索：从 IndexService.allSegments 中匹配，路径/类名/方法名权重 ×3
     */
    List<TextSegment> keywordSearch(String query, int topK) {
        List<TextSegment> allSegs = indexService.getAllSegments();
        if (allSegs.isEmpty())
            return Collections.emptyList();

        Set<String> stopWords = Set.of("的", "了", "吗", "是", "在", "和", "有", "这", "那", "什么",
                "哪里", "怎么", "如何", "为什么", "可以", "帮我", "分析", "代码", "方法", "类");
        String[] tokens = query.replaceAll("[，。？！、\\s]+", " ").split("\\s+");
        List<String> keywords = Arrays.stream(tokens)
                .filter(t -> t.length() >= 2 && !stopWords.contains(t))
                .map(String::toLowerCase)
                .toList();

        if (keywords.isEmpty())
            return Collections.emptyList();

        return allSegs.stream()
                .map(seg -> {
                    String text = seg.text().toLowerCase();
                    String path = Optional.ofNullable(seg.metadata().getString("path")).orElse("").toLowerCase();
                    String className = Optional.ofNullable(seg.metadata().getString("className")).orElse("")
                            .toLowerCase();
                    String methodName = Optional.ofNullable(seg.metadata().getString("methodName")).orElse("")
                            .toLowerCase();
                    long score = keywords.stream()
                            .mapToLong(kw -> {
                                long s = 0;
                                if (path.contains(kw))
                                    s += 3;
                                if (className.contains(kw))
                                    s += 3;
                                if (methodName.contains(kw))
                                    s += 3;
                                if (text.contains(kw))
                                    s += 1;
                                return s;
                            })
                            .sum();
                    return Map.entry(score, seg);
                })
                .filter(e -> e.getKey() > 0)
                .sorted(Map.Entry.<Long, TextSegment>comparingByKey().reversed())
                .limit(topK)
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }

    private String buildContext(List<TextSegment> segments, List<EmbeddingMatch<TextSegment>> vectorMatches) {
        Map<String, Double> scoreMap = vectorMatches.stream()
                .collect(Collectors.toMap(m -> m.embedded().text(), EmbeddingMatch::score, (a, b) -> a));

        return segments.stream()
                .map(seg -> {
                    String source = seg.metadata().getString("source");
                    String prefix;
                    if ("blueprint".equals(source)) {
                        String title = seg.metadata().getString("title");
                        prefix = title != null ? "[审计报告 - " + title + "]" : "[审计报告]";
                    } else {
                        String path = seg.metadata().getString("path");
                        String className = seg.metadata().getString("className");
                        String methodName = seg.metadata().getString("methodName");
                        String startLine = seg.metadata().getString("startLine");
                        String endLine = seg.metadata().getString("endLine");
                        StringBuilder prefixSb = new StringBuilder("[代码: ");
                        if (path != null)
                            prefixSb.append(path);
                        if (className != null)
                            prefixSb.append(" | 类: ").append(className);
                        if (methodName != null)
                            prefixSb.append(" | 方法: ").append(methodName);
                        if (startLine != null && endLine != null)
                            prefixSb.append(" | L").append(startLine).append("-").append(endLine);
                        prefixSb.append("]");
                        prefix = prefixSb.toString();
                    }
                    Double score = scoreMap.get(seg.text());
                    String scoreStr = score != null
                            ? " (相似度: " + String.format("%.2f", score) + ")"
                            : " (关键字匹配)";
                    return prefix + scoreStr + "\n" + seg.text();
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    public record ChatResult(String answer, List<String> sources) {
    }

    private record RagContext(String prompt, List<String> sources) {
    }
}
