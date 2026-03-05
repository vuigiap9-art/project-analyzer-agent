package com.bupt.cqy.analyzer.controller;

import com.bupt.cqy.analyzer.model.ChatRequest;
import com.bupt.cqy.analyzer.model.ChatResponse;
import com.bupt.cqy.analyzer.model.CodeSnippet;
import com.bupt.cqy.analyzer.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AnalysisController {

    private final CodeCrawlerService codeCrawlerService;
    private final LogicAuditService logicAuditService;
    private final ProjectSummarizerService projectSummarizerService;
    private final IndexService indexService;
    private final RagChatService ragChatService;
    private final ProjectIndexManager projectIndexManager;
    private final InteractiveAuditService interactiveAuditService;

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
            String auditReport = logicAuditService.auditProject(snippets);

            // Step 3: Save blueprint
            log.info("Step 3: Saving project blueprint...");
            projectSummarizerService.saveBlueprint(auditReport, path);

            // Step 4: 向量化索引（RAG 联动）
            log.info("Step 4: Indexing for RAG...");
            ProjectIndexManager.LoadedProject loaded = projectIndexManager.indexAndPersist(path, auditReport, snippets);

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("alreadyIndexed", false);
            response.put("projectId", loaded.projectId());
            response.put("filesScanned", snippets.size());
            response.put("auditReport", auditReport);
            response.put("blueprint", auditReport);
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
            @RequestParam(defaultValue = "false") boolean force) {
        log.info("Received interactive analysis request for path: {}", path);
        try {
            String projectId = projectIndexManager.projectIdForPath(path);

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
                response.put("message", "Project already indexed. Loaded from persisted RAG store.");
                return ResponseEntity.ok(response);
            }

            // Step 1: Agentic Interactive Audit (生成高质量蓝图)
            log.info("Step 1: Starting interactive agent audit...");
            String auditReport = interactiveAuditService.interactiveAudit(path);

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
            RagChatService.ChatResult result = ragChatService.chat(projectId, request.getQuestion());
            return ResponseEntity.ok(new ChatResponse(result.answer(), result.sources()));

        } catch (Exception e) {
            log.error("Chat failed", e);
            return ResponseEntity.internalServerError()
                    .body(new ChatResponse("对话失败: " + e.getMessage(), List.of()));
        }
    }

    /**
     * 流式 RAG 对话接口（SSE）。
     * 前端通过 EventSource 连接，逐 token 接收流式响应。
     * 事件格式：sources → token x N → done
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam String projectId, @RequestParam String question) {
        log.info("Received streaming chat request: {}", question);
        return ragChatService.chatStream(projectId, question);
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
}
