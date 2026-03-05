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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
                如果你觉得已经收集到了足够清晰的项目脉络、业务分层、可能存在的风险，
                请你停止调用工具，直接总结出你收集到的所有架构信息和调查笔记（越详细越好，这将作为第二阶段生成的原材料）。
                """)
        String chat(@UserMessage String message);
    }

    public String interactiveAudit(String projectPath) {
        try {
            // 拿到代码地骨架图
            ProjectMap projectMap = projectMapService.buildProjectMap(projectPath);
            String projectMapJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(projectMap);

            log.info("Agent starting dynamic architectural exploration on: {}", projectPath);

            AuditTools tools = new AuditTools(projectPath);

            // 组装具备记忆和工具自动调度能力 Agent
            DynamicAuditor auditor = AiServices.builder(DynamicAuditor.class)
                    .chatLanguageModel(v3ChatModel)
                    .chatMemory(MessageWindowChatMemory.withMaxMessages(30)) // 能记住过去 30 轮探索，避免“拆东墙忘西墙”
                    .tools(tools)
                    .build();

            String initialPrompt = String.format("""
                    项目架构树（Project-Map.json）如下：
                    ```json
                    %s
                    ```
                    现在请你开始自由探查，不要急于下结论。翻遍重要接口后，告诉我你的探底笔记！
                    """, projectMapJson);

            // 阻塞等待，底层会自动多次请求 API 以调用各种 Tool 并喂还结果，直至最终输出普通文字
            String explorationNotes = auditor.chat(initialPrompt);
            log.info("Agent exploration completed. Notes len: {}", explorationNotes.length());

            // --- 第二阶段：深度推理机提纯报告 ---
            log.info("Reasoner executing ultimate blueprint synthesis...");

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

                    ==================================
                    【探查探底笔记】：
                    %s
                    ==================================
                    【项目骨架地图】：
                    %s
                    ==================================

                    现在，请直接输出最终的高保真 Blueprint 文本：
                    """, explorationNotes, projectMapJson);

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

        public AuditTools(String projectRoot) {
            this.projectRoot = projectRoot;
        }

        @Tool("读取指定文件从 startLine 到 endLine 的代码内容，供分析使用。")
        public String readFileSnippet(
                @P("项目骨架地图中的文件相对路径（e.g. src/main/java/App.java）") String path,
                @P("起始行号（从 1 开始）") int startLine,
                @P("结束行号（包头包尾）") int endLine) {

            log.info("Agent Tool Call -> request read: {} (lines: {}-{})", path, startLine, endLine);
            if (path == null) {
                return "【系统拒绝】路径不能为空";
            }

            try {
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

                // Token/字节 硬截断防护：每次最多允许读 600 行
                int limit = 600;
                if (end - start + 1 > limit) {
                    end = start + limit - 1;
                    String content = String.join("\n", lines.subList(start - 1, end));
                    return "【系统警告】单次请求行数超过防护极限(" + limit + " 行截图机制激活)。\n" +
                            "已截断至第 " + end + " 行！若需其余部分请发起新 tool_call。内容如下：\n" + content;
                }

                return String.join("\n", lines.subList(start - 1, end));

            } catch (Exception e) {
                log.warn("工具拦截异常: {}", e.getMessage());
                return "【系统反馈】文件读取崩溃: " + e.getMessage();
            }
        }
    }
}
