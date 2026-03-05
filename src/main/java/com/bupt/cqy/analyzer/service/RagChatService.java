package com.bupt.cqy.analyzer.service;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagChatService {

    private static final String SYSTEM_PROMPT = """
            你是一位拥有 20 年经验的资深架构师，正在基于已有的项目审计报告和代码片段回答开发者的问题。

            请严格基于以下检索到的上下文信息回答问题。如果上下文中没有相关信息，请坦诚说明。
            回答应当准确、专业、有深度，并尽可能引用具体的代码位置或审计结论。
            回答中请使用 Markdown 格式，便于阅读。
            """;

    private final EmbeddingModel embeddingModel;
    private final ChatLanguageModel reasonerChatModel;
    private final ChatLanguageModel toolChatModel;
    private final StreamingChatLanguageModel streamingChatModel;
    private final ProjectIndexManager projectIndexManager;
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();
    private final ExecutorService auditExecutor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(1);

    @Value("${rag.chat.vector.top-k:4}")
    private int vectorTopK;

    @Value("${rag.chat.keyword.top-k:2}")
    private int keywordTopK;

    @Value("${rag.chat.min-score:0.35}")
    private double minScore;

    @Value("${rag.chat.max-context-chars:8000}")
    private int maxContextChars;

    @Value("${rag.chat.max-memory-turns:4}")
    private int maxMemoryTurns;

    @Value("${rag.chat.max-memory-context-chars:2500}")
    private int maxMemoryContextChars;

    @Value("${rag.chat.enable.agentic.audit:false}")
    private boolean enableAgenticAudit;

    @Value("${rag.chat.agentic.audit.timeout.ms:8000}")
    private long agenticAuditTimeoutMs;

    @Value("${rag.chat.max-agentic-notes.chars:3000}")
    private int maxAgenticNotesChars;

    @Value("${rag.chat.sse.timeout.ms:600000}")
    private long sseTimeoutMs;

    @Value("${rag.chat.sse.heartbeat.ms:15000}")
    private long sseHeartbeatMs;

    public RagChatService(
            EmbeddingModel embeddingModel,
            @Qualifier("deepSeekChatModel") ChatLanguageModel reasonerChatModel,
            @Qualifier("deepSeekV3ChatModel") ChatLanguageModel toolChatModel,
            StreamingChatLanguageModel streamingChatModel,
            ProjectIndexManager projectIndexManager) {
        this.embeddingModel = embeddingModel;
        this.reasonerChatModel = reasonerChatModel;
        this.toolChatModel = toolChatModel;
        this.streamingChatModel = streamingChatModel;
        this.projectIndexManager = projectIndexManager;
    }

    private RagContext buildRagContext(ProjectIndexManager.LoadedProject project, String sessionId, String userQuestion) {
        String normalizedSessionId = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;

        Embedding queryEmbedding = embeddingModel.embed(userQuestion).content();
        List<EmbeddingMatch<TextSegment>> vectorMatches = project.store().findRelevant(queryEmbedding, vectorTopK,
            minScore);
        log.info("向量检索命中 {} 个片段", vectorMatches.size());

        List<TextSegment> keywordMatches = keywordSearch(project, userQuestion, keywordTopK);
        log.info("关键字检索命中 {} 个片段", keywordMatches.size());

        Map<String, TextSegment> mergedMap = new LinkedHashMap<>();
        for (EmbeddingMatch<TextSegment> m : vectorMatches) {
            mergedMap.putIfAbsent(m.embedded().text(), m.embedded());
        }
        for (TextSegment seg : keywordMatches) {
            mergedMap.putIfAbsent(seg.text(), seg);
        }
        List<TextSegment> mergedSegments = new ArrayList<>(mergedMap.values());

        String context = buildContext(mergedSegments, vectorMatches);
        List<ProjectIndexManager.ChatMemoryTurn> recentMemory = projectIndexManager.readRecentChatMemory(
                project.projectId(),
                normalizedSessionId,
            Math.max(1, maxMemoryTurns));
        String memoryContext = truncateText(buildRecentMemoryContext(recentMemory), maxMemoryContextChars);
        String projectMapJson = projectIndexManager.loadProjectMapJson(project.projectId());
        String trimmedContext = truncateText(context, maxContextChars);

        String auditNotes = "";
        if (enableAgenticAudit && shouldRunAgenticAudit(userQuestion)) {
            auditNotes = buildAgenticAuditNotes(
                project.meta().rootPath(),
                projectMapJson,
                userQuestion,
                trimmedContext,
                memoryContext);
        }

        LinkedHashSet<String> sourceSet = mergedSegments.stream()
                .map(seg -> {
                    String source = seg.metadata().getString("source");
                    if ("code".equals(source)) {
                        return seg.metadata().getString("path");
                    }
                    if ("chat-memory".equals(source)) {
                        return "chat-memory";
                    }
                    return "Project-Blueprint.md";
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (!projectMapJson.isBlank()) {
            sourceSet.add("Project-Map.json");
        }

        String augmentedPrompt = String.format("""
                %s

                ## 检索到的上下文信息

                %s

                ## 会话历史（最近多轮）

                %s

                ## 交互式审计补充（基于 Project-Map 与按需文件读取）

                %s

                ## 用户问题

                %s

                请基于以上上下文信息，给出准确、专业的回答。
                """, SYSTEM_PROMPT,
                trimmedContext,
                memoryContext.isBlank() ? "（当前会话暂无历史）" : memoryContext,
                auditNotes.isBlank() ? "（未触发额外审计工具调用）" : auditNotes,
                userQuestion);

        return new RagContext(augmentedPrompt, new ArrayList<>(sourceSet), normalizedSessionId);
    }

    public SseEmitter chatStream(String projectId, String sessionId, String userQuestion) {
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);

        streamExecutor.submit(() -> {
            AtomicBoolean completed = new AtomicBoolean(false);
            ScheduledFuture<?> heartbeatFuture = scheduleHeartbeat(emitter, completed);
            try {
                log.info("收到流式提问：{}", userQuestion);
                ProjectIndexManager.LoadedProject project = projectIndexManager.load(projectId);
                RagContext ctx = buildRagContext(project, sessionId, userQuestion);
                StringBuilder answerBuffer = new StringBuilder();

                String sourcesJson = ctx.sources().stream()
                        .map(s -> "\"" + s.replace("\"", "\\\"") + "\"")
                        .collect(Collectors.joining(",", "[", "]"));
                emitter.send(SseEmitter.event().name("sources").data(sourcesJson));

                streamingChatModel.generate(ctx.prompt(), new StreamingResponseHandler<>() {
                    @Override
                    public void onNext(String token) {
                        try {
                            answerBuffer.append(token);
                            emitter.send(SseEmitter.event().name("token").data(token));
                        } catch (IOException e) {
                            log.warn("SSE 推送中断: {}", e.getMessage());
                        }
                    }

                    @Override
                    public void onComplete(Response<AiMessage> response) {
                        try {
                            projectIndexManager.appendChatMemoryAndIndex(
                                    projectId,
                                    ctx.sessionId(),
                                    userQuestion,
                                    answerBuffer.toString());
                                completed.set(true);
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
                        completed.set(true);
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
                completed.set(true);
                emitter.completeWithError(e);
            } finally {
                completed.set(true);
                if (heartbeatFuture != null) {
                    heartbeatFuture.cancel(true);
                }
            }
        });

        return emitter;
    }

    public ChatResult chat(String projectId, String sessionId, String userQuestion) {
        log.info("收到用户提问：{}", userQuestion);
        ProjectIndexManager.LoadedProject project = projectIndexManager.load(projectId);
        RagContext ctx = buildRagContext(project, sessionId, userQuestion);
        String answer = reasonerChatModel.generate(ctx.prompt());
        projectIndexManager.appendChatMemoryAndIndex(projectId, ctx.sessionId(), userQuestion, answer);
        log.info("RAG 回答生成完毕，引用 {} 个来源", ctx.sources().size());
        return new ChatResult(answer, ctx.sources());
    }

    private String buildRecentMemoryContext(List<ProjectIndexManager.ChatMemoryTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        return turns.stream()
                .map(t -> "- Q" + t.turn() + ": " + t.question() + "\n  A" + t.turn() + ": " + t.answer())
                .collect(Collectors.joining("\n"));
    }

    private String buildAgenticAuditNotes(
            String projectRoot,
            String projectMapJson,
            String userQuestion,
            String retrievedContext,
            String memoryContext) {
        try {
            RagAuditTools tools = new RagAuditTools(projectRoot, projectMapJson);
            RagAuditAssistant assistant = AiServices.builder(RagAuditAssistant.class)
                    .chatLanguageModel(toolChatModel)
                    .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                    .tools(tools)
                    .build();

            String prompt = String.format("""
                    用户问题：%s

                    已检索上下文：
                    %s

                    最近会话历史：
                    %s

                    请你必要时读取 Project-Map 和源码片段进行补充审计，输出“审计补充结论”（不要重复原检索内容）。
                    """,
                    userQuestion,
                    truncateText(retrievedContext, 5000),
                    memoryContext.isBlank() ? "（暂无）" : truncateText(memoryContext, 2000));

            return CompletableFuture
                    .supplyAsync(() -> assistant.audit(prompt), auditExecutor)
                    .orTimeout(agenticAuditTimeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        if (ex instanceof TimeoutException) {
                            log.warn("交互式审计补充超时 {}ms，已降级跳过", agenticAuditTimeoutMs);
                        } else {
                            log.warn("交互式审计补充失败，已降级跳过: {}", ex.getMessage());
                        }
                        return "";
                    })
                    .thenApply(notes -> truncateText(notes, maxAgenticNotesChars))
                    .join();
        } catch (Exception e) {
            log.warn("交互式审计补充失败，降级为纯检索回答: {}", e.getMessage());
            return "";
        }
    }

    private boolean shouldRunAgenticAudit(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = question.toLowerCase();
        return q.contains("源码")
                || q.contains("调用链")
                || q.contains("实现")
                || q.contains("脚本")
                || q.contains("漏洞")
                || q.contains("函数")
                || q.contains("方法");
    }

    private ScheduledFuture<?> scheduleHeartbeat(SseEmitter emitter, AtomicBoolean completed) {
        if (sseHeartbeatMs <= 0) {
            return null;
        }
        return heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (completed.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
            } catch (IOException e) {
                completed.set(true);
            }
        }, sseHeartbeatMs, sseHeartbeatMs, TimeUnit.MILLISECONDS);
    }

    private String truncateText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars) + "\n...（已截断）";
    }

    List<TextSegment> keywordSearch(ProjectIndexManager.LoadedProject project, String query, int topK) {
        List<TextSegment> allSegs = project.segments();
        if (allSegs.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> stopWords = Set.of("的", "了", "吗", "是", "在", "和", "有", "这", "那", "什么",
                "哪里", "怎么", "如何", "为什么", "可以", "帮我", "分析", "代码", "方法", "类");
        String[] tokens = query.replaceAll("[，。？！、\\s]+", " ").split("\\s+");
        List<String> keywords = Arrays.stream(tokens)
                .filter(t -> t.length() >= 2 && !stopWords.contains(t))
                .map(String::toLowerCase)
                .toList();

        if (keywords.isEmpty()) {
            return Collections.emptyList();
        }

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
                                if (path.contains(kw)) {
                                    s += 3;
                                }
                                if (className.contains(kw)) {
                                    s += 3;
                                }
                                if (methodName.contains(kw)) {
                                    s += 3;
                                }
                                if (text.contains(kw)) {
                                    s += 1;
                                }
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
                    } else if ("chat-memory".equals(source)) {
                        String role = Optional.ofNullable(seg.metadata().getString("role")).orElse("unknown");
                        String turn = Optional.ofNullable(seg.metadata().getString("turn")).orElse("?");
                        prefix = "[对话记忆 | turn=" + turn + " | role=" + role + "]";
                    } else {
                        String path = seg.metadata().getString("path");
                        String className = seg.metadata().getString("className");
                        String methodName = seg.metadata().getString("methodName");
                        String startLine = seg.metadata().getString("startLine");
                        String endLine = seg.metadata().getString("endLine");
                        StringBuilder prefixSb = new StringBuilder("[代码: ");
                        if (path != null) {
                            prefixSb.append(path);
                        }
                        if (className != null) {
                            prefixSb.append(" | 类: ").append(className);
                        }
                        if (methodName != null) {
                            prefixSb.append(" | 方法: ").append(methodName);
                        }
                        if (startLine != null && endLine != null) {
                            prefixSb.append(" | L").append(startLine).append("-").append(endLine);
                        }
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

    private record RagContext(String prompt, List<String> sources, String sessionId) {
    }

    interface RagAuditAssistant {
        @SystemMessage("""
                你是代码交互式审计助手。
                你可以使用工具读取 Project-Map 和文件片段，补充回答所需的技术事实。
                只输出审计补充结论，不要输出工具调用过程。
                """)
        String audit(@UserMessage String prompt);
    }

    static class RagAuditTools {
        private final String projectRoot;
        private final String projectMapJson;

        RagAuditTools(String projectRoot, String projectMapJson) {
            this.projectRoot = projectRoot;
            this.projectMapJson = projectMapJson == null ? "" : projectMapJson;
        }

        @Tool("读取当前项目的 Project-Map.json 内容")
        public String readProjectMap() {
            if (projectMapJson.isBlank()) {
                return "Project-Map 不存在";
            }
            return projectMapJson;
        }

        @Tool("读取指定文件从 startLine 到 endLine 的代码片段")
        public String readFileSnippet(
                @P("文件相对路径") String path,
                @P("起始行号，从1开始") int startLine,
                @P("结束行号") int endLine) {
            if (path == null || path.isBlank()) {
                return "路径不能为空";
            }
            try {
                Path root = Path.of(projectRoot).toAbsolutePath().normalize();
                Path file = root.resolve(path).normalize();
                if (!file.startsWith(root) || !Files.exists(file) || !Files.isRegularFile(file)) {
                    return "文件不存在或越权访问";
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.isEmpty()) {
                    return "文件为空";
                }
                int start = Math.max(1, startLine);
                int end = Math.min(lines.size(), endLine);
                if (start > end) {
                    return "行号范围无效";
                }
                int limit = 400;
                if (end - start + 1 > limit) {
                    end = start + limit - 1;
                }
                return String.join("\n", lines.subList(start - 1, end));
            } catch (Exception e) {
                return "读取文件失败: " + e.getMessage();
            }
        }
    }
}
