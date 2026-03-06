package com.bupt.cqy.analyzer.controller;

import com.bupt.cqy.analyzer.model.ChatRequest;
import com.bupt.cqy.analyzer.model.ChatResponse;
import com.bupt.cqy.analyzer.model.CodeSnippet;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bupt.cqy.analyzer.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AnalysisController {

    private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile("(?is)<think>(.*?)</think>");
    private final ExecutorService analysisStreamExecutor = Executors.newCachedThreadPool();

    private final CodeCrawlerService codeCrawlerService;
    private final LogicAuditService logicAuditService;
    private final ProjectSummarizerService projectSummarizerService;
    private final IndexService indexService;
    private final RagChatService ragChatService;
    private final ProjectIndexManager projectIndexManager;
    private final InteractiveAuditService interactiveAuditService;
    private final ProjectMapService projectMapService;
    private final ObjectMapper objectMapper;

    @GetMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyzeProject(
            @RequestParam String path,
            @RequestParam(defaultValue = "false") boolean force) {
        log.info("Received analysis request for path: {}", path);

        try {
            String projectId = projectIndexManager.projectIdForPath(path);

            // 如果已存在索引且不强制重建，直接复用
            if (!force && projectIndexManager.isIndexed(projectId)) {
                ProjectIndexManager.LoadedProject project = projectIndexManager.load(projectId);
                Map<String, Object> response = new HashMap<>();
                response.put("status", "success");
                response.put("alreadyIndexed", true);
                response.put("projectId", projectId);
                response.put("filesScanned", project.meta().filesScanned());
                response.put("auditReport", project.blueprint());
                response.put("blueprint", project.blueprint());
                response.put("reasoning", "");
                response.put("reasonerUsed", false);
                response.put("message", "Project already indexed. Loaded from persisted RAG store.");
                return ResponseEntity.ok(response);
            }

            // Step 1: Scan project
            log.info("Step 1: Scanning project...");
            List<CodeSnippet> snippets = codeCrawlerService.scanProject(path);

            if (snippets.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "No supported code files found in the specified path"));
            }

            // Step 2: Perform logic audit
            log.info("Step 2: Performing logic audit...");
            String auditReportRaw = logicAuditService.auditProject(snippets);
            ThinkingExtraction extraction = extractThinking(auditReportRaw);
            String auditReport = extraction.content().isBlank() ? auditReportRaw : extraction.content();

            // Step 3: Save blueprint
            log.info("Step 3: Saving project blueprint...");
            projectSummarizerService.saveBlueprint(auditReport, path);

            // Step 4: 向量化索引（RAG 联动）
            log.info("Step 4: Indexing for RAG...");
            ProjectIndexManager.LoadedProject loaded = projectIndexManager.indexAndPersist(path, auditReport, snippets);

            // Step 5: 持久化 Project-Map 到 data/projects/<projectId>
            log.info("Step 5: Persisting Project-Map...");
            projectMapService.buildAndPersistProjectMap(path, loaded.projectId());

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("alreadyIndexed", false);
            response.put("projectId", loaded.projectId());
            response.put("filesScanned", snippets.size());
            response.put("auditReport", auditReport);
            response.put("blueprint", auditReport);
            response.put("reasoning", extraction.reasoning());
            response.put("reasonerUsed", !extraction.reasoning().isBlank());
            response.put("message", "Analysis completed successfully. Blueprint saved and indexed for RAG.");

            log.info("Analysis completed successfully for path: {}", path);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Analysis failed for path: {}", path, e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * 交互式审计接口：
     * - DeepSeek 先基于 Project-Map.json 规划审计路径
     * - 通过 COMMAND_BLOCK 主动请求查看具体文件
     * - 后端按需读取源码并多轮喂回，最终生成蓝图
     */
    @GetMapping("/analyze/interactive")
    public ResponseEntity<Map<String, Object>> analyzeProjectInteractive(
            @RequestParam String path,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(required = false) Integer auditMaxCalls,
            @RequestParam(required = false) Integer auditMaxTotalLines,
            @RequestParam(required = false) Integer auditMaxLinesPerCall,
            @RequestParam(required = false) Integer auditTargetLinesPerCall,
            @RequestParam(required = false) Integer auditMinLinesPerCall,
            @RequestParam(required = false) Integer auditMaxVisitedFiles) {
        log.info("Received interactive analysis request for path: {}", path);
        try {
            String projectId = projectIndexManager.projectIdForPath(path);
            InteractiveAuditService.AuditRuntimeOptions runtimeOptions = buildAuditRuntimeOptions(
                    auditMaxCalls,
                    auditMaxTotalLines,
                    auditMaxLinesPerCall,
                    auditTargetLinesPerCall,
                    auditMinLinesPerCall,
                    auditMaxVisitedFiles);

            if (!force && projectIndexManager.isIndexed(projectId)) {
                ProjectIndexManager.LoadedProject project = projectIndexManager.load(projectId);
                Map<String, Object> response = new HashMap<>();
                response.put("status", "success");
                response.put("mode", "interactive");
                response.put("alreadyIndexed", true);
                response.put("projectId", projectId);
                response.put("filesScanned", project.meta().filesScanned());
                response.put("auditReport", project.blueprint());
                response.put("blueprint", project.blueprint());
                response.put("reasoning", "");
                response.put("reasonerUsed", false);
                response.put("message", "Project already indexed. Loaded from persisted RAG store.");
                return ResponseEntity.ok(response);
            }

            // Step 1: Agentic Interactive Audit (生成高质量蓝图)
            log.info("Step 1: Starting interactive agent audit...");
                InteractiveAuditService.InteractiveAuditResult auditResult = interactiveAuditService.interactiveAuditDetailed(path,
                    projectId,
                    null,
                    runtimeOptions);
                String auditReport = auditResult.blueprint();

            // Step 2: 把蓝图落盘，方便用户直接在根目录查看
            log.info("Step 2: Saving project blueprint markdown...");
            projectSummarizerService.saveBlueprint(auditReport, path);

            // Step 3: 快速获取全量本地文件（纯本地无 LLM 计分消耗），为 RAG 准备弹药
            log.info("Step 3: Scanning local code files for RAG chunks...");
            List<CodeSnippet> snippets = codeCrawlerService.scanProject(path);

            // Step 4: 将完美蓝图与全量代码切片编入持久化向量库
            log.info("Step 4: Indexing blueprint and code segments for RAG...");
            ProjectIndexManager.LoadedProject loaded = projectIndexManager.indexAndPersist(path, auditReport, snippets);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("mode", "interactive");
            response.put("alreadyIndexed", false);
            response.put("projectId", loaded.projectId());
            response.put("filesScanned", snippets.size());
            response.put("auditReport", auditReport);
            response.put("blueprint", auditReport);
            response.put("reasoning", auditResult.reasoning());
            response.put("reasonerUsed", auditResult.reasonerUsed());
            response.put("message", "Interactive analysis completed and indexed for RAG natively.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Interactive analysis failed for path: {}", path, e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * 实时分析进度流（SSE）。
     * 事件：progress、log、done、error
     */
    @GetMapping(value = "/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeProjectStream(
            @RequestParam String path,
            @RequestParam(defaultValue = "standard") String mode,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(required = false) Integer auditMaxCalls,
            @RequestParam(required = false) Integer auditMaxTotalLines,
            @RequestParam(required = false) Integer auditMaxLinesPerCall,
            @RequestParam(required = false) Integer auditTargetLinesPerCall,
            @RequestParam(required = false) Integer auditMinLinesPerCall,
            @RequestParam(required = false) Integer auditMaxVisitedFiles) {
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);
        InteractiveAuditService.AuditRuntimeOptions runtimeOptions = buildAuditRuntimeOptions(
                auditMaxCalls,
                auditMaxTotalLines,
                auditMaxLinesPerCall,
                auditTargetLinesPerCall,
                auditMinLinesPerCall,
                auditMaxVisitedFiles);

        analysisStreamExecutor.submit(() -> {
            try {
                sendProgress(emitter, 5, "初始化分析任务...");
                Map<String, Object> result = "interactive".equalsIgnoreCase(mode)
                        ? runInteractiveAnalysisWithProgress(path, force, emitter, runtimeOptions)
                        : runStandardAnalysisWithProgress(path, force, emitter);
                sendEvent(emitter, "done", result);
                emitter.complete();
            } catch (Exception e) {
                log.error("Streaming analysis failed for path: {}", path, e);
                try {
                    sendEvent(emitter, "error", Map.of("message", e.getMessage()));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "Project Insight Agent"));
    }

    @PostMapping("/analyze-snippet")
    public ResponseEntity<Map<String, Object>> analyzeSingleFile(@RequestBody CodeSnippet snippet) {
        log.info("Received single file analysis request: {}", snippet.getPath());

        try {
            String analysis = logicAuditService.analyzeCodeSnippet(snippet);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("file", snippet.getPath());
            response.put("language", snippet.getLanguage());
            response.put("analysis", analysis);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Single file analysis failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        log.info("Received chat request: {}", request.getQuestion());

        try {
            String projectId = request.getProjectId();
            if (projectId == null || projectId.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(new ChatResponse("缺少 projectId，请先完成 /api/analyze 或选择已索引项目。", List.of()));
            }
            RagChatService.ChatResult result = ragChatService.chat(projectId, request.getSessionId(), request.getQuestion());
                return ResponseEntity.ok(new ChatResponse(
                    result.answer(),
                    result.reasoning(),
                    result.reasonerUsed(),
                    result.sources()));

        } catch (Exception e) {
            log.error("Chat failed", e);
            return ResponseEntity.internalServerError()
                    .body(new ChatResponse("对话失败: " + e.getMessage(), List.of()));
        }
    }

    /**
     * 流式 RAG 对话接口（SSE）。
     * 前端通过 EventSource 连接，逐 token 接收流式响应。
        * 事件格式：sources → (thinking_token|token) x N → thinking_done → done
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestParam String projectId,
            @RequestParam(required = false) String sessionId,
            @RequestParam String question) {
        log.info("Received streaming chat request: {}", question);
        return ragChatService.chatStream(projectId, sessionId, question);
    }

    @GetMapping("/projects")
    public ResponseEntity<List<ProjectIndexManager.ProjectMeta>> listProjects() {
        return ResponseEntity.ok(projectIndexManager.listProjects());
    }

    @GetMapping("/projects/{projectId}")
    public ResponseEntity<Map<String, Object>> getProject(@PathVariable String projectId) {
        try {
            ProjectIndexManager.LoadedProject project = projectIndexManager.load(projectId);
            return ResponseEntity.ok(Map.of(
                    "projectId", project.projectId(),
                    "meta", project.meta(),
                    "blueprint", project.blueprint()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/projects/{projectId}")
    public ResponseEntity<Map<String, Object>> deleteProject(@PathVariable String projectId) {
        try {
            projectIndexManager.deleteProject(projectId);
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "projectId", projectId,
                    "message", "Project deleted successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()));
        }
    }

    @GetMapping("/projects/{projectId}/chat-memory")
    public ResponseEntity<Map<String, Object>> getChatMemory(
            @PathVariable String projectId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "100") int limit) {
        try {
            projectIndexManager.load(projectId);
            List<ProjectIndexManager.ChatMemoryTurn> turns = projectIndexManager.readRecentChatMemory(
                    projectId,
                    sessionId,
                    Math.max(1, limit));
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "projectId", projectId,
                    "sessionId", (sessionId == null || sessionId.isBlank()) ? "default" : sessionId,
                    "turns", turns));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()));
        }
    }

    @DeleteMapping("/projects/{projectId}/chat-memory")
    public ResponseEntity<Map<String, Object>> clearChatMemory(
            @PathVariable String projectId,
            @RequestParam(required = false) String sessionId) {
        try {
            int removed = projectIndexManager.clearChatMemory(projectId, sessionId);
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "projectId", projectId,
                    "sessionId", (sessionId == null || sessionId.isBlank()) ? "default" : sessionId,
                    "removed", removed,
                    "message", "Chat memory cleared"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()));
        }
    }

    /**
     * 目录浏览接口——供前端文件夹选择器使用。
     * 返回指定路径下的子目录和文件列表。
     */
    @GetMapping("/browse")
    public ResponseEntity<Map<String, Object>> browse(@RequestParam(defaultValue = "/") String path) {
        try {
            Path dir = Path.of(path);
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                return ResponseEntity.badRequest().body(Map.of("error", "路径不存在或不是目录: " + path));
            }

            List<Map<String, String>> entries;
            try (var stream = Files.list(dir)) {
                entries = stream
                        .sorted((a, b) -> {
                            // 目录排在前面
                            boolean aIsDir = Files.isDirectory(a);
                            boolean bIsDir = Files.isDirectory(b);
                            if (aIsDir != bIsDir)
                                return aIsDir ? -1 : 1;
                            return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
                        })
                        .map(p -> Map.of(
                                "name", p.getFileName().toString(),
                                "path", p.toString(),
                                "type", Files.isDirectory(p) ? "dir" : "file"))
                        .collect(Collectors.toList());
            }

            // 格式化当前路径的面包屑信息，供面包屑导航使用
            Path parent = dir.getParent();
            Map<String, Object> result = new HashMap<>();
            result.put("current", path);
            result.put("parent", parent != null ? parent.toString() : null);
            result.put("entries", entries);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Browse failed for path: {}", path, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> runStandardAnalysisWithProgress(String path, boolean force, SseEmitter emitter)
            throws Exception {
        sendProgress(emitter, 10, "计算项目标识...");
        String projectId = projectIndexManager.projectIdForPath(path);

        if (!force && projectIndexManager.isIndexed(projectId)) {
            sendProgress(emitter, 40, "检测到已索引项目，正在加载缓存...");
            ProjectIndexManager.LoadedProject project = projectIndexManager.load(projectId);
            sendProgress(emitter, 100, "加载完成 ✓");
            return Map.of(
                    "status", "success",
                    "mode", "standard",
                    "alreadyIndexed", true,
                    "projectId", projectId,
                    "filesScanned", project.meta().filesScanned(),
                    "auditReport", project.blueprint(),
                    "blueprint", project.blueprint(),
                    "reasoning", "",
                    "reasonerUsed", false,
                    "message", "Project already indexed. Loaded from persisted RAG store.");
        }

        sendProgress(emitter, 20, "扫描项目文件...");
        List<CodeSnippet> snippets = codeCrawlerService.scanProject(path);
        if (snippets.isEmpty()) {
            throw new IllegalArgumentException("No supported code files found in the specified path");
        }

        sendProgress(emitter, 55, "调用模型执行代码审计...");
        String auditReportRaw = logicAuditService.auditProject(snippets);
        ThinkingExtraction extraction = extractThinking(auditReportRaw);
        String auditReport = extraction.content().isBlank() ? auditReportRaw : extraction.content();

        sendProgress(emitter, 70, "保存蓝图文档...");
        projectSummarizerService.saveBlueprint(auditReport, path);

        sendProgress(emitter, 85, "构建并持久化 RAG 索引...");
        ProjectIndexManager.LoadedProject loaded = projectIndexManager.indexAndPersist(path, auditReport, snippets);

        sendProgress(emitter, 95, "生成项目地图...");
        projectMapService.buildAndPersistProjectMap(path, loaded.projectId());

        sendProgress(emitter, 100, "分析完成 ✓");
        return Map.of(
                "status", "success",
                "mode", "standard",
                "alreadyIndexed", false,
                "projectId", loaded.projectId(),
                "filesScanned", snippets.size(),
                "auditReport", auditReport,
                "blueprint", auditReport,
                "reasoning", extraction.reasoning(),
                "reasonerUsed", !extraction.reasoning().isBlank(),
                "message", "Analysis completed successfully. Blueprint saved and indexed for RAG.");
    }

        private Map<String, Object> runInteractiveAnalysisWithProgress(String path, boolean force, SseEmitter emitter,
            InteractiveAuditService.AuditRuntimeOptions runtimeOptions)
            throws Exception {
        sendProgress(emitter, 10, "计算项目标识...");
        String projectId = projectIndexManager.projectIdForPath(path);

        if (!force && projectIndexManager.isIndexed(projectId)) {
            sendProgress(emitter, 40, "检测到已索引项目，正在加载缓存...");
            ProjectIndexManager.LoadedProject project = projectIndexManager.load(projectId);
            sendProgress(emitter, 100, "加载完成 ✓");
            return Map.of(
                    "status", "success",
                    "mode", "interactive",
                    "alreadyIndexed", true,
                    "projectId", projectId,
                    "filesScanned", project.meta().filesScanned(),
                    "auditReport", project.blueprint(),
                    "blueprint", project.blueprint(),
                    "reasoning", "",
                    "reasonerUsed", false,
                    "message", "Project already indexed. Loaded from persisted RAG store.");
        }

        sendProgress(emitter, 30, "交互式探查核心代码...");
        InteractiveAuditService.InteractiveAuditResult auditResult = interactiveAuditService.interactiveAuditDetailed(path,
            projectId,
                telemetry -> {
                try {
                        sendEvent(emitter, "log", telemetry.message());
                        if (telemetry.calls() != null) {
                            sendEvent(emitter, "stats", Map.of(
                                    "calls", telemetry.calls(),
                                    "maxCalls", telemetry.maxCalls(),
                                    "visitedFiles", telemetry.visitedFiles(),
                                "maxVisitedFiles", telemetry.maxVisitedFiles(),
                                    "totalReadLines", telemetry.totalReadLines(),
                                    "maxTotalReadLines", telemetry.maxTotalReadLines(),
                                    "remainingCalls", Math.max(0, telemetry.maxCalls() - telemetry.calls()),
                                "remainingVisitedFiles", Math.max(0,
                                    telemetry.maxVisitedFiles() - telemetry.visitedFiles()),
                                    "remainingLines", Math.max(0, telemetry.maxTotalReadLines() - telemetry.totalReadLines())));
                        }
                } catch (Exception ignored) {
                }
            }, runtimeOptions);
        String auditReport = auditResult.blueprint();

        sendProgress(emitter, 55, "保存蓝图文档...");
        projectSummarizerService.saveBlueprint(auditReport, path);

        sendProgress(emitter, 70, "扫描全量代码用于 RAG...");
        List<CodeSnippet> snippets = codeCrawlerService.scanProject(path);

        sendProgress(emitter, 85, "构建并持久化 RAG 索引...");
        ProjectIndexManager.LoadedProject loaded = projectIndexManager.indexAndPersist(path, auditReport, snippets);

        sendProgress(emitter, 100, "交互式分析完成 ✓");
        return Map.of(
                "status", "success",
                "mode", "interactive",
                "alreadyIndexed", false,
                "projectId", loaded.projectId(),
                "filesScanned", snippets.size(),
                "auditReport", auditReport,
                "blueprint", auditReport,
                "reasoning", auditResult.reasoning(),
                "reasonerUsed", auditResult.reasonerUsed(),
                "message", "Interactive analysis completed and indexed for RAG natively.");
    }

    private void sendProgress(SseEmitter emitter, int percent, String text) throws Exception {
        sendEvent(emitter, "progress", Map.of("percent", percent, "text", text));
    }

    private void sendEvent(SseEmitter emitter, String event, Object payload) throws Exception {
        String data = payload instanceof String ? (String) payload : objectMapper.writeValueAsString(payload);
        emitter.send(SseEmitter.event().name(event).data(data));
    }

    private InteractiveAuditService.AuditRuntimeOptions buildAuditRuntimeOptions(
            Integer auditMaxCalls,
            Integer auditMaxTotalLines,
            Integer auditMaxLinesPerCall,
            Integer auditTargetLinesPerCall,
            Integer auditMinLinesPerCall,
            Integer auditMaxVisitedFiles) {
        return new InteractiveAuditService.AuditRuntimeOptions(
                auditMaxCalls,
                auditMaxTotalLines,
                auditMaxLinesPerCall,
                auditTargetLinesPerCall,
                auditMinLinesPerCall,
                auditMaxVisitedFiles);
    }

    private ThinkingExtraction extractThinking(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return new ThinkingExtraction("", "");
        }
        Matcher matcher = THINK_BLOCK_PATTERN.matcher(rawText);
        List<String> reasoningBlocks = new ArrayList<>();
        while (matcher.find()) {
            String block = matcher.group(1);
            if (block != null && !block.isBlank()) {
                reasoningBlocks.add(block.trim());
            }
        }
        String content = THINK_BLOCK_PATTERN.matcher(rawText).replaceAll("").trim();
        String reasoning = String.join("\n\n", reasoningBlocks).trim();
        return new ThinkingExtraction(content, reasoning);
    }

    private record ThinkingExtraction(String content, String reasoning) {
    }
}
