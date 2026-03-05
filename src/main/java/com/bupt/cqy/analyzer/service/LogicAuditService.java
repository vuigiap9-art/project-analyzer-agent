package com.bupt.cqy.analyzer.service;

import com.bupt.cqy.analyzer.model.CodeSnippet;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogicAuditService {

    private final AuditAssistant auditAssistant;
    private final IndexService indexService;

    private static final int MAX_SEGMENT_PREVIEW_LENGTH = 300;

    public String auditProject(List<CodeSnippet> snippets) {
        log.info("Starting logic audit for {} code snippets", snippets.size());

        StringBuilder projectData = new StringBuilder();
        projectData.append("# Project Analysis Data\n\n");
        projectData.append("## Metadata\n");
        projectData.append("- Total Files: ").append(snippets.size()).append("\n");
        projectData.append("- Languages: ");

        long javaCount = snippets.stream().filter(s -> "Java".equals(s.getLanguage())).count();
        long pythonCount = snippets.stream().filter(s -> "Python".equals(s.getLanguage())).count();
        long cppCount = snippets.stream().filter(s -> "C++".equals(s.getLanguage())).count();
        long goCount = snippets.stream().filter(s -> "Go".equals(s.getLanguage())).count();

        projectData.append(String.format("Java(%d), Python(%d), C++(%d), Go(%d)\n\n",
                javaCount, pythonCount, cppCount, goCount));

        // 使用结构化摘要而不是直接附上全部源码，减少首轮审计的上下文体积
        projectData.append("## File Overview\n\n");
        for (CodeSnippet snippet : snippets) {
            String[] lines = snippet.getCode().split("\n", -1);
            projectData.append("- File: ").append(snippet.getPath())
                    .append(" (").append(snippet.getLanguage())
                    .append(", ").append(lines.length).append(" lines)\n");
        }

        projectData.append("\n## Structural Summary (Per Function / Method)\n\n");
        for (CodeSnippet snippet : snippets) {
            List<TextSegment> segments = indexService.splitCodeByStructure(snippet);
            if (segments.isEmpty()) {
                continue;
            }

            projectData.append("### File: ").append(snippet.getPath()).append("\n\n");
            for (TextSegment seg : segments) {
                Map<String, Object> md = seg.metadata().toMap();
                String language = (String) md.getOrDefault("language", snippet.getLanguage());
                String className = (String) md.getOrDefault("className", null);
                String methodName = (String) md.getOrDefault("methodName", null);
                String startLine = (String) md.getOrDefault("startLine", null);
                String endLine = (String) md.getOrDefault("endLine", null);

                projectData.append("- Segment: ");
                if (className != null) {
                    projectData.append(className).append(".");
                }
                if (methodName != null) {
                    projectData.append(methodName);
                } else {
                    projectData.append("(file-level)");
                }
                if (startLine != null && endLine != null) {
                    projectData.append(" [L").append(startLine).append("-").append(endLine).append("]");
                }
                projectData.append("\n");

                String text = seg.text();
                String preview = text.length() > MAX_SEGMENT_PREVIEW_LENGTH
                        ? text.substring(0, MAX_SEGMENT_PREVIEW_LENGTH) + "\n...\n"
                        : text;

                projectData.append("```").append(language.toLowerCase()).append("\n");
                projectData.append(preview).append("\n");
                projectData.append("```\n\n");
            }
        }

        String analysisInput = projectData.toString();
        log.info("Sending {} characters to DeepSeek for analysis", analysisInput.length());

        String report = auditAssistant.analyzeLogic(analysisInput);

        log.info("Audit completed, report generated");
        return report;
    }

    public String analyzeCodeSnippet(CodeSnippet snippet) {
        log.info("Analyzing single code snippet: {}", snippet.getPath());

        String input = String.format("""
                # Single File Analysis

                **File**: %s
                **Language**: %s

                ```%s
                %s
                ```

                Please provide a focused analysis of this file, highlighting any issues and improvement suggestions.
                """,
                snippet.getPath(),
                snippet.getLanguage(),
                snippet.getLanguage().toLowerCase(),
                snippet.getCode());

        return auditAssistant.analyzeLogic(input);
    }
}
