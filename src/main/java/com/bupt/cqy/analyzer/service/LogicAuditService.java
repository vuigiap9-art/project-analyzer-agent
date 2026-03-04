package com.bupt.cqy.analyzer.service;

import com.bupt.cqy.analyzer.model.CodeSnippet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogicAuditService {

    private final AuditAssistant auditAssistant;

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

        projectData.append("## File List and Code Content\n\n");

        for (CodeSnippet snippet : snippets) {
            projectData.append("### File: ").append(snippet.getPath()).append("\n");
            projectData.append("**Language**: ").append(snippet.getLanguage()).append("\n\n");
            projectData.append("```").append(snippet.getLanguage().toLowerCase()).append("\n");
            projectData.append(snippet.getCode()).append("\n");
            projectData.append("```\n\n");
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
