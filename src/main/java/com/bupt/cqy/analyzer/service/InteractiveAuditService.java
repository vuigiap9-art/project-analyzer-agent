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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Value("${interactive.audit.max.file.read.calls:40}")
    private int maxFileReadCalls;

    @Value("${interactive.audit.max.total.read.lines:8000}")
    private int maxTotalReadLines;

    @Value("${interactive.audit.max.lines.per.call:240}")
    private int maxLinesPerCall;

    @Value("${interactive.audit.max.repeated.reads:2}")
    private int maxRepeatedReads;

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
                如果你觉得已经收集到了足够清晰的项目脉络、业务分层、可能存在的风险，
                请你停止调用工具，直接总结出你收集到的所有架构信息和调查笔记（越详细越好，这将作为第二阶段生成的原材料）。
                """)
        String chat(@UserMessage String message);
    }

    public String interactiveAudit(String projectPath, String projectId) {
        try {
            // 拿到代码地骨架图
            ProjectMap projectMap = projectMapService.buildAndPersistProjectMap(projectPath, projectId);
                String compactProjectMapJson = projectMapService.toCompactMapJson(projectMap, mapMaxFilesInPrompt);

            log.info("Agent starting dynamic architectural exploration on: {}", projectPath);

                AuditTools tools = new AuditTools(projectPath, maxFileReadCalls, maxTotalReadLines, maxLinesPerCall,
                    maxRepeatedReads);

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

                    现在请你开始“最小充分探查”：抓主链路，避免重复读取，达到可解释结论后立即停止工具调用并输出探底笔记。
                    """, compactProjectMapJson, maxFileReadCalls, maxTotalReadLines, maxLinesPerCall);

            // 阻塞等待，底层会自动多次请求 API 以调用各种 Tool 并喂还结果，直至最终输出普通文字
            String explorationNotes = auditor.chat(initialPrompt);
                explorationNotes = trimTo(explorationNotes, maxExplorationNotesChars);
            log.info("Agent exploration completed. Notes len: {}", explorationNotes.length());

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
            String finalBlueprint = reasonerChatModel.generate(synthesisPrompt);

            return finalBlueprint;

        } catch (Exception e) {
            log.error("Interactive audit failed: {}", e.getMessage(), e);
            return "# Audit Report (Interactive)\n\n" +
                    "审计架构解析异常: " + e.getMessage() + "\n";
        }
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

        private int readCalls = 0;
        private int totalReadLines = 0;
        private final Set<String> visitedFiles = new HashSet<>();
        private final Map<String, Integer> rangeReadCounter = new HashMap<>();

        public AuditTools(String projectRoot, int maxCalls, int maxTotalLines, int perCallLimit, int maxSameRangeReads) {
            this.projectRoot = projectRoot;
            this.maxCalls = maxCalls;
            this.maxTotalLines = maxTotalLines;
            this.perCallLimit = perCallLimit;
            this.maxSameRangeReads = maxSameRangeReads;
        }

        @Tool("读取指定文件从 startLine 到 endLine 的代码内容，供分析使用。")
        public synchronized String readFileSnippet(
                @P("项目骨架地图中的文件相对路径（e.g. src/main/java/App.java）") String path,
                @P("起始行号（从 1 开始）") int startLine,
                @P("结束行号（包头包尾）") int endLine) {

            log.info("Agent Tool Call -> request read: {} (lines: {}-{})", path, startLine, endLine);
            if (path == null) {
                return "【系统拒绝】路径不能为空";
            }

            try {
                if (readCalls >= maxCalls) {
                    return "【系统预算告警】工具读取次数已达上限(" + maxCalls
                            + ")。请停止继续读取，基于已掌握证据直接输出探查总结。";
                }

                Path root = Path.of(projectRoot).toAbsolutePath().normalize();
                Path file = root.resolve(path).normalize();

                // 安全逃逸校验
                if (!file.startsWith(root) || !Files.exists(file) || !Files.isRegularFile(file)) {
                    return "【系统拒绝】找不到文件或非法越权访问: " + path;
                }

                // 阻止炸库类型拓展名
                String name = file.getFileName().toString().toLowerCase();
                if (name.endsWith(".class") || name.endsWith(".jar") || name.endsWith(".png") ||
                        name.endsWith(".jpg") || name.endsWith(".pdf") || name.endsWith(".exe")) {
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
                    return "【系统反馈】请求行号不合理，该文件仅有 " + totalLines + " 行，请修正您的访问范围！";
                }

                // 防重复读取同一范围导致卡死
                String rangeKey = path + ":" + start + "-" + end;
                int repeated = rangeReadCounter.getOrDefault(rangeKey, 0);
                if (repeated >= maxSameRangeReads) {
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
                    return "【系统预算告警】总读取行数接近上限，已仅返回剩余额度(" + willRead + " 行)：\n"
                            + String.join("\n", lines.subList(start - 1, end));
                }

                readCalls++;
                totalReadLines += willRead;
                visitedFiles.add(path);
                rangeReadCounter.put(rangeKey, repeated + 1);

                return String.join("\n", lines.subList(start - 1, end));

            } catch (Exception e) {
                log.warn("工具拦截异常: {}", e.getMessage());
                return "【系统反馈】文件读取崩溃: " + e.getMessage();
            }
        }

        public String budgetSummary() {
            return "calls=" + readCalls + "/" + maxCalls + ", totalLines=" + totalReadLines + "/" + maxTotalLines
                    + ", visitedFiles=" + visitedFiles.size();
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
}
