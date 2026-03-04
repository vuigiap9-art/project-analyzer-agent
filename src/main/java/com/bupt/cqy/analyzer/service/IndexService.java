package com.bupt.cqy.analyzer.service;

import com.bupt.cqy.analyzer.model.CodeSnippet;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexService {

    private static final int MIN_CHUNK_LENGTH = 50;
    // 整文件保持入库的行数阈值，小于此值不再细拆
    private static final int SMALL_FILE_LINE_THRESHOLD = 100;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    /**
     * 全量可检索的 segment 列表，供关键字混合检索使用。
     * 使用 CopyOnWriteArrayList 保证并发安全。
     */
    @Getter
    private final List<TextSegment> allSegments = new CopyOnWriteArrayList<>();

    /**
     * 将审计报告和代码片段全部向量化并存入内存向量库。
     * - 报告：按 Markdown 章节（## 标题）切分
     * - 代码：按方法/函数/类级别切分（Java/Python），其他语言整文件入库
     */
    public void indexAll(String report, List<CodeSnippet> snippets) {
        log.info("开始向量化索引：报告 + {} 个代码片段", snippets.size());
        allSegments.clear();

        List<TextSegment> segments = new ArrayList<>();

        // 1. 按 Markdown 二级/三级标题切分审计报告
        List<String> sections = splitByMarkdownHeadings(report);
        int reportChunkCount = 0;
        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.length() < MIN_CHUNK_LENGTH)
                continue;

            Metadata metadata = Metadata.from("source", "blueprint");
            String title = extractFirstHeading(trimmed);
            if (title != null)
                metadata.put("title", title);

            TextSegment seg = TextSegment.from(trimmed, metadata);
            segments.add(seg);
            reportChunkCount++;
        }
        log.info("报告按章节切分为 {} 个有效块", reportChunkCount);

        // 2. 按结构切分代码片段（方法/类级别）
        int codeChunkCount = 0;
        for (CodeSnippet snippet : snippets) {
            List<TextSegment> codeSegs = splitCodeByStructure(snippet);
            segments.addAll(codeSegs);
            codeChunkCount += codeSegs.size();
        }
        log.info("代码片段按结构切分为 {} 个块（来自 {} 个文件）", codeChunkCount, snippets.size());

        // 3. 批量向量化并存入向量库
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);

        // 4. 保存全量 segments 用于关键字检索
        allSegments.addAll(segments);

        log.info("向量化索引完成：共 {} 个段落（报告 {} + 代码 {}）",
                segments.size(), reportChunkCount, codeChunkCount);
    }

    /**
     * 按代码语言将 CodeSnippet 拆分为方法/函数级别的 TextSegment。
     * - Java：识别 class 定义 + 方法签名，每个方法一个 chunk，带类名前缀
     * - Python：按 def/class 关键字拆分
     * - 小文件（< SMALL_FILE_LINE_THRESHOLD 行）或其他语言：整文件为一个 chunk
     * - 每个 chunk 的 metadata 包含
     * source/path/language/className/methodName/startLine/endLine
     */
    List<TextSegment> splitCodeByStructure(CodeSnippet snippet) {
        String code = snippet.getCode();
        String language = snippet.getLanguage();
        String[] lines = code.split("\n", -1);

        // 小文件或不支持结构化拆分的语言：整文件入库
        if (lines.length < SMALL_FILE_LINE_THRESHOLD
                || (!language.equalsIgnoreCase("Java") && !language.equalsIgnoreCase("Python"))) {
            return List.of(buildCodeSegment(snippet, code, null, null, 1, lines.length));
        }

        return switch (language.toLowerCase()) {
            case "java" -> splitJavaCode(snippet, lines);
            case "python" -> splitPythonCode(snippet, lines);
            default -> List.of(buildCodeSegment(snippet, code, null, null, 1, lines.length));
        };
    }

    /**
     * Java 代码按 class 和方法边界切分。
     * 策略：正则识别方法签名行，然后按花括号深度跟踪每个方法的结束行。
     */
    private List<TextSegment> splitJavaCode(CodeSnippet snippet, String[] lines) {
        List<TextSegment> result = new ArrayList<>();

        // 提取类名
        String className = extractJavaClassName(lines);

        // 识别方法签名：访问修饰符 + 返回类型 + 方法名(...)
        Pattern methodPattern = Pattern.compile(
                "^\\s*(public|private|protected|static|final|synchronized|default|abstract|override)" +
                        "[\\w\\s<>\\[\\],?@]*\\s+(\\w+)\\s*\\([^)]*\\)\\s*(throws\\s+[\\w,\\s]+)?\\s*\\{");

        List<int[]> methodRanges = new ArrayList<>();
        List<String> methodNames = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            Matcher m = methodPattern.matcher(lines[i]);
            if (m.find()) {
                // 从该行起跟踪花括号深度，找到方法结束行
                int depth = countChar(lines[i], '{') - countChar(lines[i], '}');
                int end = i;
                for (int j = i + 1; j < lines.length && depth > 0; j++) {
                    depth += countChar(lines[j], '{') - countChar(lines[j], '}');
                    end = j;
                }
                methodRanges.add(new int[] { i, end });
                methodNames.add(m.group(2));
            }
        }

        if (methodRanges.isEmpty()) {
            // 没有识别到方法，整文件入库
            return List.of(buildCodeSegment(snippet, String.join("\n", lines), className, null, 1, lines.length));
        }

        for (int i = 0; i < methodRanges.size(); i++) {
            int start = methodRanges.get(i)[0];
            int end = methodRanges.get(i)[1];
            String methodName = methodNames.get(i);
            // chunk 内容：类名注释 + 方法代码
            StringBuilder sb = new StringBuilder();
            if (className != null)
                sb.append("// class: ").append(className).append("\n");
            for (int l = start; l <= end; l++)
                sb.append(lines[l]).append("\n");
            result.add(buildCodeSegment(snippet, sb.toString().trim(), className, methodName, start + 1, end + 1));
        }

        return result.isEmpty()
                ? List.of(buildCodeSegment(snippet, String.join("\n", lines), className, null, 1, lines.length))
                : result;
    }

    /**
     * Python 代码按 def/class 关键字切分。
     * 策略：遇到 def/class 就作为新 chunk 的起始行，上一个 chunk 截止。
     */
    private List<TextSegment> splitPythonCode(CodeSnippet snippet, String[] lines) {
        List<TextSegment> result = new ArrayList<>();
        Pattern defClass = Pattern.compile("^(class|def)\\s+(\\w+)");

        int chunkStart = 0;
        String currentName = null;
        String currentClass = null;

        for (int i = 0; i <= lines.length; i++) {
            boolean isEnd = (i == lines.length);
            boolean isBoundary = !isEnd && defClass.matcher(lines[i]).find();

            if (isBoundary || isEnd) {
                if (i > chunkStart) {
                    StringBuilder sb = new StringBuilder();
                    for (int l = chunkStart; l < i; l++)
                        sb.append(lines[l]).append("\n");
                    String chunkText = sb.toString().trim();
                    if (chunkText.length() >= MIN_CHUNK_LENGTH) {
                        result.add(buildCodeSegment(snippet, chunkText, currentClass, currentName, chunkStart + 1, i));
                    }
                }
                if (!isEnd) {
                    Matcher m = defClass.matcher(lines[i]);
                    if (m.find()) {
                        if ("class".equals(m.group(1)))
                            currentClass = m.group(2);
                        currentName = m.group(2);
                    }
                    chunkStart = i;
                }
            }
        }

        return result.isEmpty()
                ? List.of(buildCodeSegment(snippet, snippet.getCode(), null, null, 1, lines.length))
                : result;
    }

    /** 构建一个代码 TextSegment，metadata 包含完整定位信息 */
    private TextSegment buildCodeSegment(CodeSnippet snippet, String text,
            String className, String methodName,
            int startLine, int endLine) {
        Metadata metadata = Metadata.from("source", "code");
        metadata.put("path", snippet.getPath());
        metadata.put("language", snippet.getLanguage());
        if (className != null)
            metadata.put("className", className);
        if (methodName != null)
            metadata.put("methodName", methodName);
        metadata.put("startLine", String.valueOf(startLine));
        metadata.put("endLine", String.valueOf(endLine));
        return TextSegment.from(text, metadata);
    }

    /** 从 Java 源码行中提取 class/interface/enum/record 名称 */
    private String extractJavaClassName(String[] lines) {
        Pattern classPattern = Pattern.compile(
                "^\\s*(?:public|private|protected|abstract|final)?\\s*(?:class|interface|enum|record)\\s+(\\w+)");
        for (String line : lines) {
            Matcher m = classPattern.matcher(line);
            if (m.find())
                return m.group(1);
        }
        return null;
    }

    /** 计算字符串中某字符出现的次数（用于花括号深度追踪） */
    private int countChar(String s, char target) {
        int count = 0;
        for (char c : s.toCharArray())
            if (c == target)
                count++;
        return count;
    }

    // ──────────────── 报告分块 ────────────────

    /** 按 Markdown ## 或 ### 标题行切分文档 */
    private List<String> splitByMarkdownHeadings(String markdown) {
        List<String> chunks = new ArrayList<>();
        Pattern pattern = Pattern.compile("(?=^#{2,3}\\s)", Pattern.MULTILINE);
        String[] parts = pattern.split(markdown);

        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty())
                chunks.add(trimmed);
        }

        // 降级：按双空行切分并合并短段
        if (chunks.size() <= 1) {
            chunks.clear();
            String[] paragraphs = markdown.split("\n\n");
            StringBuilder buffer = new StringBuilder();
            for (String para : paragraphs) {
                String trimmedPara = para.trim();
                if (trimmedPara.isEmpty())
                    continue;
                buffer.append(trimmedPara).append("\n\n");
                if (buffer.length() >= 200) {
                    chunks.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
            }
            if (!buffer.isEmpty())
                chunks.add(buffer.toString().trim());
        }

        return chunks;
    }

    /** 从 chunk 文本中提取第一个 Markdown 标题 */
    private String extractFirstHeading(String text) {
        Matcher matcher = Pattern.compile("^#{1,4}\\s+(.+)$", Pattern.MULTILINE).matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
