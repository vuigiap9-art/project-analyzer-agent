package com.bupt.cqy.analyzer.service;

import com.bupt.cqy.analyzer.model.ProjectMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 交互式审计 orchestrator：
 * - 第一阶段：配置一个 LangChain4j Agent，带有 `readFileSnippet` 工具。
 * 它能看到 Project-Map.json，由于搭载了 MessageWindowChatMemory，
 * 在多轮自我推演中，它能主动根据情况翻阅任意文件内容。
 * - 第二阶段：等第一阶段 Agent 收集并输出“探查笔记”后，
 * 交给 DeepSeek Reasoner 通过强推理能力提纯生成一份精致的蓝图。
 */
@Slf4j
@Service
public class InteractiveAuditService {

    private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile("(?is)<think>(.*?)</think>");

    @Value("${interactive.audit.max.file.read.calls:40}")
    private int maxFileReadCalls;

    @Value("${interactive.audit.max.total.read.lines:8000}")
    private int maxTotalReadLines;

    @Value("${interactive.audit.max.lines.per.call:240}")
    private int maxLinesPerCall;

    @Value("${interactive.audit.target.lines.per.call:160}")
    private int targetLinesPerCall;

    @Value("${interactive.audit.min.lines.per.call:120}")
    private int minLinesPerCall;

    @Value("${interactive.audit.max.repeated.reads:2}")
    private int maxRepeatedReads;

    @Value("${interactive.audit.max.visited.files:120}")
    private int maxVisitedFiles;

    @Value("${interactive.audit.max.overlap.ratio:0.80}")
    private double maxOverlapRatio;

    @Value("${interactive.audit.max.inefficient.calls:6}")
    private int maxInefficientCalls;

    @Value("${interactive.audit.map.max.files.in.prompt:160}")
    private int mapMaxFilesInPrompt;

    @Value("${interactive.audit.max.exploration.notes.chars:20000}")
    private int maxExplorationNotesChars;

    @Value("${interactive.audit.max.map.chars.in.synthesis:24000}")
    private int maxMapCharsInSynthesis;

    private final ChatLanguageModel v3ChatModel;
    private final ChatLanguageModel reasonerChatModel;
    private final ProjectMapService projectMapService;
    private final ObjectMapper objectMapper;

    // 显式注入不同版本的模型，v3 用于 Tool Calling，reasoner 用于最后大总结
    public InteractiveAuditService(
            @Qualifier("deepSeekV3ChatModel") ChatLanguageModel v3ChatModel,
            @Qualifier("deepSeekChatModel") ChatLanguageModel reasonerChatModel,
            ProjectMapService projectMapService,
            ObjectMapper objectMapper) {
        this.v3ChatModel = v3ChatModel;
        this.reasonerChatModel = reasonerChatModel;
        this.projectMapService = projectMapService;
        this.objectMapper = objectMapper;
    }

    /**
     * 第一阶段的 Agent 接口
     */
    interface DynamicAuditor {
        @SystemMessage("""
                你是一名高级代码护卫和架构分析师（基于 DeepSeek V3）。

                下面你会收到该项目的【全局架构树】。
                请你自行判断，然后使用 `readFileSnippet` 工具查看任何你认为“核心且值得审核”的文件。
                如果你觉得一个文件行数太多，可以通过调节 startLine 和 endLine 分段查看。
                你必须先从入口、controller、service 三类文件开始，优先定位主链路，不要盲扫。
                严禁重复读取同一范围。必要时扩大窗口，而不是重复请求相同区间。
                如果工具反馈“重复覆盖/预算告警/无效调用”，请立即调整策略或直接收敛输出，不要继续重复试探。
                如果你觉得已经收集到了足够清晰的项目脉络、业务分层、可能存在的风险，
                请你停止调用工具，直接总结出你收集到的所有架构信息和调查笔记（越详细越好，这将作为第二阶段生成的原材料）。
                """)
        String chat(@UserMessage String message);
    }

    public record InteractiveAuditResult(String blueprint, String reasoning, boolean reasonerUsed) {
    }

    public record AuditTelemetry(
            String message,
            Integer calls,
            Integer maxCalls,
            Integer visitedFiles,
            Integer maxVisitedFiles,
            Integer totalReadLines,
            Integer maxTotalReadLines) {
    }

    public record AuditRuntimeOptions(
            Integer maxFileReadCalls,
            Integer maxTotalReadLines,
            Integer maxLinesPerCall,
            Integer targetLinesPerCall,
            Integer minLinesPerCall,
            Integer maxVisitedFiles) {
    }

    private record EffectiveAuditConfig(
            int maxFileReadCalls,
            int maxTotalReadLines,
            int maxLinesPerCall,
            int targetLinesPerCall,
            int minLinesPerCall,
            int maxVisitedFiles) {
    }

    public String interactiveAudit(String projectPath, String projectId) {
        return interactiveAuditDetailed(projectPath, projectId, null).blueprint();
    }

    public InteractiveAuditResult interactiveAuditDetailed(String projectPath, String projectId) {
        return interactiveAuditDetailed(projectPath, projectId, null);
    }

    public InteractiveAuditResult interactiveAuditDetailed(String projectPath, String projectId,
            Consumer<AuditTelemetry> eventSink) {
        return interactiveAuditDetailed(projectPath, projectId, eventSink, null);
    }

    public InteractiveAuditResult interactiveAuditDetailed(String projectPath, String projectId,
            Consumer<AuditTelemetry> eventSink,
            AuditRuntimeOptions runtimeOptions) {
        try {
            EffectiveAuditConfig config = resolveConfig(runtimeOptions);
            emit(eventSink, "[interactive] 构建 Project-Map...");
            // 拿到代码地骨架图
            ProjectMap projectMap = projectMapService.buildAndPersistProjectMap(projectPath, projectId);
            String compactProjectMapJson = projectMapService.toCompactMapJson(projectMap, mapMaxFilesInPrompt);

            log.info("Agent starting dynamic architectural exploration on: {}", projectPath);
            emit(eventSink, "[interactive] 启动 Agent 探查...");

            AuditTools tools = new AuditTools(projectPath, config.maxFileReadCalls(), config.maxTotalReadLines(),
                    config.maxLinesPerCall(), maxRepeatedReads, maxOverlapRatio, maxInefficientCalls,
                    config.minLinesPerCall(), config.targetLinesPerCall(), config.maxVisitedFiles(), eventSink);

            // 组装具备记忆和工具自动调度能力 Agent
            DynamicAuditor auditor = AiServices.builder(DynamicAuditor.class)
                    .chatLanguageModel(v3ChatModel)
                    .chatMemory(MessageWindowChatMemory.withMaxMessages(18))
                    .tools(tools)
                    .build();

            String initialPrompt = String.format("""
                    项目架构树（Project-Map.json）如下：
                    ```json
                    %s
                    ```
                    探查预算约束：
                    - 总工具读取次数上限：%d
                    - 总读取行数上限：%d
                    - 单次读取最大行数：%d
                    - 单次读取目标行数：%d
                    - 单次读取最小建议：%d
                    - 可触达文件上限：%d

                    现在请你开始“最小充分探查”：
                    1) 每次尽量读取 %d-%d 行，避免碎片化 20-60 行的小窗读取。
                    2) 优先大窗口读取关键文件（controller/service/核心入口），减少总 tool_call 次数。
                    3) 达到可解释结论后立即停止工具调用并输出探底笔记。
                    """, compactProjectMapJson, config.maxFileReadCalls(), config.maxTotalReadLines(),
                    config.maxLinesPerCall(), config.targetLinesPerCall(), config.minLinesPerCall(),
                    config.maxVisitedFiles(), config.minLinesPerCall(), config.targetLinesPerCall());

            // 阻塞等待，底层会自动多次请求 API 以调用各种 Tool 并喂还结果，直至最终输出普通文字
            String explorationNotes;
            try {
                explorationNotes = auditor.chat(initialPrompt);
            } catch (Exception firstEx) {
                emit(eventSink, "[interactive] Agent 探查异常，尝试恢复: " + firstEx.getMessage());
                if (isMalformedToolJson(firstEx)) {
                    String retryPrompt = initialPrompt + "\n\n重要：后续 tool 参数必须是合法 JSON 对象，禁止输出截断 JSON。若多次失败请立即停止工具调用并输出总结。";
                    try {
                        explorationNotes = auditor.chat(retryPrompt);
                        emit(eventSink, "[interactive] 恢复成功：已通过重试继续探查");
                    } catch (Exception secondEx) {
                        emit(eventSink, "[interactive] 重试失败，降级为骨架总结模式");
                        explorationNotes = buildFallbackExplorationNotes(projectMap, secondEx);
                    }
                } else {
                    emit(eventSink, "[interactive] 非 JSON 工具异常，降级为骨架总结模式");
                    explorationNotes = buildFallbackExplorationNotes(projectMap, firstEx);
                }
            }
            explorationNotes = trimTo(explorationNotes, maxExplorationNotesChars);
            log.info("Agent exploration completed. Notes len: {}", explorationNotes.length());
            emit(eventSink, "[interactive] Agent 探查完成，开始 reasoner 总结...");

            // --- 第二阶段：深度推理机提纯报告 ---
            log.info("Reasoner executing ultimate blueprint synthesis...");

            String synthesisMap = trimTo(compactProjectMapJson, maxMapCharsInSynthesis);
            String budgetSummary = tools.budgetSummary();

            String synthesisPrompt = String.format("""
                    你是一名超级架构分析大师 (基于 DeepSeek-Reasoner)。

                    刚才有一个探查 Agent 对项目源码进行了自动化动态探索，生成了粗略甚至带有工具日志记录的【探查笔记】。
                    你现在的任务是：仔细阅读这批“探查笔记”以及全量“骨架地图”，
                    提炼整个项目的核心逻辑、业务线、使用框架、设计模式、以及存在的技术风险。

                    将所有这一切整合为一份【纯粹、专业、极致排版】的《完整项目分析蓝图 (Project Blueprint)》。

                    要求：
                    1. 抛弃探查者“接下来我要去看XX文件”的过渡语气和废话。
                    2. 只阐述纯结果、纯架构、纯代码思想。
                    3. 使用精美的 Markdown（适当安排表格、多级标题、以及少量 mermaid 架构草图如果需要）。
                    4. 对“证据覆盖不足”的部分明确标注“待补充验证”，不要臆断。
                          5. 若输出 Mermaid，请保证语法严格可解析：
                              - 仅使用 ASCII 引号 ("), 不要使用中文引号（“”）。
                              - 不要在节点 id 中使用空格或中文标点，节点文本放在 [] 内。
                              - 若无法确保语法正确，则不要输出 mermaid 代码块。

                    ==================================
                    【探查探底笔记】：
                    %s
                    ==================================
                    【探查预算与覆盖统计】：
                    %s
                    ==================================
                    【项目骨架地图】：
                    %s
                    ==================================

                    现在，请直接输出最终的高保真 Blueprint 文本：
                    """, explorationNotes, budgetSummary, synthesisMap);

            // 让 Reasoner 通过超大上下文一锤定音
            String finalBlueprintRaw = reasonerChatModel.generate(synthesisPrompt);
            ThinkingExtraction extraction = extractThinking(finalBlueprintRaw);
            emit(eventSink, "[interactive] 交互式审计完成");

            return new InteractiveAuditResult(
                    extraction.content().isBlank() ? finalBlueprintRaw : extraction.content(),
                    extraction.reasoning(),
                    true);

        } catch (Exception e) {
            log.error("Interactive audit failed: {}", e.getMessage(), e);
                return new InteractiveAuditResult(
                    "# Audit Report (Interactive)\n\n" +
                        "审计架构解析异常: " + e.getMessage() + "\n",
                    "",
                    false);
        }
    }

        private EffectiveAuditConfig resolveConfig(AuditRuntimeOptions runtimeOptions) {
        int resolvedMaxCalls = choosePositive(runtimeOptions == null ? null : runtimeOptions.maxFileReadCalls(),
            maxFileReadCalls);
        int resolvedMaxTotalLines = choosePositive(runtimeOptions == null ? null : runtimeOptions.maxTotalReadLines(),
            maxTotalReadLines);
        int resolvedPerCallLimit = choosePositive(runtimeOptions == null ? null : runtimeOptions.maxLinesPerCall(),
            maxLinesPerCall);
        int resolvedMinLines = choosePositive(runtimeOptions == null ? null : runtimeOptions.minLinesPerCall(),
            minLinesPerCall);
        int resolvedTargetLines = choosePositive(runtimeOptions == null ? null : runtimeOptions.targetLinesPerCall(),
            targetLinesPerCall);
        int resolvedMaxVisitedFiles = choosePositive(runtimeOptions == null ? null : runtimeOptions.maxVisitedFiles(),
            maxVisitedFiles);

        resolvedMinLines = Math.min(resolvedMinLines, resolvedPerCallLimit);
        resolvedTargetLines = Math.max(resolvedMinLines, resolvedTargetLines);
        resolvedTargetLines = Math.min(resolvedTargetLines, resolvedPerCallLimit);

        return new EffectiveAuditConfig(
            resolvedMaxCalls,
            resolvedMaxTotalLines,
            resolvedPerCallLimit,
            resolvedTargetLines,
            resolvedMinLines,
            resolvedMaxVisitedFiles);
        }

        private int choosePositive(Integer override, int defaultValue) {
        if (override == null || override <= 0) {
            return defaultValue;
        }
        return override;
        }

    /**
     * 内部工具类：负责直接面对真实硬盘资源，由 LangChain4j 在底层通过反射调用。
     */
    class AuditTools {
        private final String projectRoot;
        private final int maxCalls;
        private final int maxTotalLines;
        private final int perCallLimit;
        private final int maxSameRangeReads;
        private final double overlapRatioLimit;
        private final int maxInefficientCalls;
        private final int minLinesPerCall;
        private final int targetLinesPerCall;
        private final int maxVisitedFiles;
        private final Consumer<AuditTelemetry> eventSink;

        private int readCalls = 0;
        private int totalReadLines = 0;
        private int inefficientCalls = 0;
        private final Set<String> visitedFiles = new HashSet<>();
        private final Map<String, Integer> rangeReadCounter = new HashMap<>();
        private final Map<String, List<Range>> fileRanges = new HashMap<>();

        public AuditTools(String projectRoot, int maxCalls, int maxTotalLines, int perCallLimit, int maxSameRangeReads,
                double overlapRatioLimit, int maxInefficientCalls, int minLinesPerCall, int targetLinesPerCall,
                int maxVisitedFiles, Consumer<AuditTelemetry> eventSink) {
            this.projectRoot = projectRoot;
            this.maxCalls = maxCalls;
            this.maxTotalLines = maxTotalLines;
            this.perCallLimit = perCallLimit;
            this.maxSameRangeReads = maxSameRangeReads;
            this.overlapRatioLimit = overlapRatioLimit;
            this.maxInefficientCalls = maxInefficientCalls;
            this.minLinesPerCall = Math.max(1, minLinesPerCall);
            this.targetLinesPerCall = Math.max(this.minLinesPerCall, targetLinesPerCall);
            this.maxVisitedFiles = Math.max(1, maxVisitedFiles);
            this.eventSink = eventSink;
        }

        @Tool("读取指定文件从 startLine 到 endLine 的代码内容，供分析使用。")
        public synchronized String readFileSnippet(
                @P("项目骨架地图中的文件相对路径（e.g. src/main/java/App.java）") String path,
                @P("起始行号（从 1 开始）") int startLine,
                @P("结束行号（包头包尾）") int endLine) {

            log.info("Agent Tool Call -> request read: {} (lines: {}-{})", path, startLine, endLine);
            emitTool(eventSink, "[tool] readFileSnippet " + path + " L" + startLine + "-" + endLine);
            if (inefficientCalls >= maxInefficientCalls) {
                emitTool(eventSink, "[tool] 已锁定收敛模式，拒绝继续读取");
                return "【系统收敛告警】连续低效工具调用过多，已进入收敛模式。请停止读取并立即输出阶段性探查总结。";
            }
            if (path == null) {
                return "【系统拒绝】路径不能为空";
            }

            try {
                if (readCalls >= maxCalls) {
                    emitTool(eventSink, "[tool] 预算耗尽: 调用次数上限");
                    return "【系统预算告警】工具读取次数已达上限(" + maxCalls
                            + ")。请停止继续读取，基于已掌握证据直接输出探查总结。";
                }

                Path root = Path.of(projectRoot).toAbsolutePath().normalize();
                Path file = root.resolve(path).normalize();

                // 安全逃逸校验
                if (!file.startsWith(root) || !Files.exists(file) || !Files.isRegularFile(file)) {
                    inefficientCalls++;
                    return "【系统拒绝】找不到文件或非法越权访问: " + path;
                }

                // 阻止炸库类型拓展名
                String name = file.getFileName().toString().toLowerCase();
                if (name.endsWith(".class") || name.endsWith(".jar") || name.endsWith(".png") ||
                        name.endsWith(".jpg") || name.endsWith(".pdf") || name.endsWith(".exe")) {
                    inefficientCalls++;
                    return "【系统拒绝】文件格式属二进制或非纯文本，Agent 无权读取！";
                }

                // 读取所有行
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.isEmpty()) {
                    return "【系统反馈】该文件为空。";
                }

                int totalLines = lines.size();
                int start = Math.max(1, startLine);
                int end = Math.min(totalLines, endLine);

                // 行号校验
                if (start > end || start > totalLines) {
                    inefficientCalls++;
                    return "【系统反馈】请求行号不合理，该文件仅有 " + totalLines + " 行，请修正您的访问范围！";
                }

                if (!visitedFiles.contains(path) && visitedFiles.size() >= maxVisitedFiles) {
                    emitTool(eventSink, "[tool] 文件预算耗尽: visitedFiles=" + visitedFiles.size());
                    return "【系统预算告警】已触达文件数达到上限(" + maxVisitedFiles
                            + ")。请基于已读文件收敛输出，不要继续扩散文件范围。";
                }

                int requestedLen = end - start + 1;
                if (requestedLen < minLinesPerCall) {
                    int desiredEnd = Math.min(totalLines, start + targetLinesPerCall - 1);
                    if (desiredEnd > end) {
                        end = desiredEnd;
                        emitTool(eventSink, "[tool] 自动扩窗到 L" + start + "-" + end + " 以减少调用次数");
                    }
                }

                List<Range> ranges = fileRanges.computeIfAbsent(path, key -> new ArrayList<>());
                int reqLen = end - start + 1;
                for (Range range : ranges) {
                    int overlap = overlapLength(start, end, range.start(), range.end());
                    double overlapRatio = reqLen <= 0 ? 0 : (double) overlap / reqLen;
                    if (overlapRatio >= overlapRatioLimit) {
                        inefficientCalls++;
                        emitTool(eventSink,
                            "[tool] 重复覆盖拦截 " + path + " overlap=" + String.format("%.2f", overlapRatio));
                        maybeForceConverge();
                        return "【系统预算告警】检测到高重叠读取（overlap=" + String.format("%.2f", overlapRatio)
                                + "），请改为读取未覆盖区间或直接输出总结。";
                    }
                }

                // 防重复读取同一范围导致卡死
                String rangeKey = path + ":" + start + "-" + end;
                int repeated = rangeReadCounter.getOrDefault(rangeKey, 0);
                if (repeated >= maxSameRangeReads) {
                    inefficientCalls++;
                    emitTool(eventSink, "[tool] 重复区间拦截 " + rangeKey);
                    maybeForceConverge();
                    return "【系统预算告警】检测到重复读取同一区间(" + rangeKey + ")次数过多。请改为读取相邻区间或直接输出总结。";
                }

                // Token/字节 硬截断防护：每次最多允许读 perCallLimit 行
                if (end - start + 1 > perCallLimit) {
                    end = start + perCallLimit - 1;
                    String content = String.join("\n", lines.subList(start - 1, end));
                    readCalls++;
                    totalReadLines += (end - start + 1);
                    visitedFiles.add(path);
                    rangeReadCounter.put(rangeKey, repeated + 1);
                        ranges.add(new Range(start, end));
                        inefficientCalls = 0;
                        emitTool(eventSink,
                            "[tool] 已返回(截断) " + path + " L" + start + "-" + end + " | " + budgetSummary());
                    return "【系统警告】单次请求行数超过防护极限(" + perCallLimit + " 行截图机制激活)。\n" +
                            "已截断至第 " + end + " 行！若需其余部分请发起新 tool_call。内容如下：\n" + content;
                }

                int willRead = end - start + 1;
                if (totalReadLines + willRead > maxTotalLines) {
                    int remaining = maxTotalLines - totalReadLines;
                    if (remaining <= 0) {
                        return "【系统预算告警】总读取行数预算已耗尽(" + maxTotalLines
                                + ")。请停止读取并输出探查结论。";
                    }
                    end = start + remaining - 1;
                    willRead = end - start + 1;
                    readCalls++;
                    totalReadLines += willRead;
                    visitedFiles.add(path);
                    rangeReadCounter.put(rangeKey, repeated + 1);
                        ranges.add(new Range(start, end));
                        inefficientCalls = 0;
                        emitTool(eventSink,
                            "[tool] 已返回(预算尾段) " + path + " L" + start + "-" + end + " | " + budgetSummary());
                    return "【系统预算告警】总读取行数接近上限，已仅返回剩余额度(" + willRead + " 行)：\n"
                            + String.join("\n", lines.subList(start - 1, end));
                }

                readCalls++;
                totalReadLines += willRead;
                visitedFiles.add(path);
                rangeReadCounter.put(rangeKey, repeated + 1);
                ranges.add(new Range(start, end));
                inefficientCalls = 0;
                emitTool(eventSink, "[tool] 已返回 " + path + " L" + start + "-" + end + " | " + budgetSummary());

                return String.join("\n", lines.subList(start - 1, end));

            } catch (Exception e) {
                log.warn("工具拦截异常: {}", e.getMessage());
                inefficientCalls++;
                maybeForceConverge();
                return "【系统反馈】文件读取崩溃: " + e.getMessage();
            }
        }

        public String budgetSummary() {
            return "calls=" + readCalls + "/" + maxCalls + ", totalLines=" + totalReadLines + "/" + maxTotalLines
                    + ", visitedFiles=" + visitedFiles.size() + "/" + maxVisitedFiles
                    + ", readWindow=" + minLinesPerCall + "-" + targetLinesPerCall + "-" + perCallLimit;
        }

        private void maybeForceConverge() {
            if (inefficientCalls >= maxInefficientCalls) {
                emitTool(eventSink, "[tool] 触发强制收敛，连续低效调用=" + inefficientCalls);
            }
        }

        private int overlapLength(int aStart, int aEnd, int bStart, int bEnd) {
            int start = Math.max(aStart, bStart);
            int end = Math.min(aEnd, bEnd);
            return Math.max(0, end - start + 1);
        }

        private record Range(int start, int end) {
        }

        private void emitTool(Consumer<AuditTelemetry> sink, String message) {
            if (sink == null) {
                return;
            }
            sink.accept(new AuditTelemetry(
                    message,
                    readCalls,
                    maxCalls,
                    visitedFiles.size(),
                    maxVisitedFiles,
                    totalReadLines,
                    maxTotalLines));
        }
    }

    private String trimTo(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n...（已按预算截断）";
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

    private void emit(Consumer<AuditTelemetry> sink, String message) {
        if (sink != null && message != null && !message.isBlank()) {
            sink.accept(new AuditTelemetry(message, null, null, null, null, null, null));
        }
    }

    private boolean isMalformedToolJson(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && (msg.contains("MalformedJsonException")
                    || msg.contains("Unterminated object")
                    || msg.contains("JsonSyntaxException"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String buildFallbackExplorationNotes(ProjectMap map, Exception cause) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Fallback Exploration Notes\n\n");
        sb.append("由于交互式工具调用异常，已降级为骨架驱动总结。\n\n");
        sb.append("- 降级原因: ").append(cause.getMessage()).append("\n");
        sb.append("- 项目根目录: ").append(map.getProjectRoot()).append("\n");
        sb.append("- 文件总数: ").append(map.getTotalFiles()).append("\n");
        sb.append("- 语言分布: ").append(map.getLanguageStats()).append("\n\n");

        List<ProjectMap.FileInfo> keyFiles = map.getFiles().stream()
                .sorted((a, b) -> Long.compare(b.getLineCount(), a.getLineCount()))
                .limit(20)
                .toList();

        sb.append("## Key Files\n\n");
        for (ProjectMap.FileInfo file : keyFiles) {
            sb.append("- ").append(file.getPath())
                    .append(" | ").append(file.getLanguage())
                    .append(" | lines=").append(file.getLineCount())
                    .append(" | roles=").append(file.getRoles())
                    .append("\n");
        }
        sb.append("\n请在最终蓝图中对高风险结论标注“待补充验证”。\n");
        return sb.toString();
    }
}
