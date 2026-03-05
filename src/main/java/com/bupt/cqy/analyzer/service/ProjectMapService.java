package com.bupt.cqy.analyzer.service;

import com.bupt.cqy.analyzer.model.ProjectMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 构建轻量级 Project-Map.json，用于指导交互式审计。
 * 只读取必要的元信息，不把源码内容塞进模型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectMapService {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".java", ".py", ".cpp", ".go", ".js");

    private static final Map<String, String> EXTENSION_TO_LANGUAGE = Map.of(
            ".java", "Java",
            ".py", "Python",
            ".cpp", "C++",
            ".go", "Go",
            ".js", "JavaScript");

    private static final Set<String> IGNORED_DIRS = Set.of(
            "node_modules", ".git", "target", "bin", "build", ".idea", ".vscode", "dist", "out");

    private final ObjectMapper objectMapper;
    private final ProjectIndexManager projectIndexManager;

    public ProjectMap buildAndPersistProjectMap(String rootPath, String projectId) throws IOException {
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            throw new IOException("Path does not exist or is not a directory: " + root);
        }

        List<ProjectMap.FileInfo> files;
        try (Stream<Path> paths = Files.walk(root)) {
            files = paths
                    .filter(Files::isRegularFile)
                    .filter(this::isSupportedFile)
                    .filter(this::isNotInIgnoredDir)
                    .map(p -> toFileInfo(root, p))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }

        Map<String, Long> languageStats = files.stream()
                .collect(Collectors.groupingBy(ProjectMap.FileInfo::getLanguage, Collectors.counting()));

        List<String> entryPoints = files.stream()
                .map(ProjectMap.FileInfo::getPath)
                .filter(this::looksLikeEntryPoint)
                .limit(20)
                .toList();

        ProjectMap map = new ProjectMap(
                root.toString(),
                languageStats,
                files.size(),
                entryPoints,
                files);

        // 持久化到 data/projects/<projectId>/Project-Map.json
        try {
            projectIndexManager.persistProjectMap(projectId, map);
            Path jsonPath = projectIndexManager.projectDir(projectId).resolve("Project-Map.json");
            log.info("Project-Map.json saved to {}", jsonPath.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to write Project-Map.json: {}", e.getMessage());
        }

        return map;
    }

    public String toCompactMapJson(ProjectMap map, int maxFilesInPrompt) throws IOException {
        int limit = Math.max(20, maxFilesInPrompt);
        List<Map<String, Object>> keyFiles = map.getFiles().stream()
                .sorted((a, b) -> Integer.compare(scoreFileImportance(b), scoreFileImportance(a)))
                .limit(limit)
                .map(file -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("path", file.getPath());
                    item.put("language", file.getLanguage());
                    item.put("lineCount", file.getLineCount());
                    item.put("roles", file.getRoles());
                    return item;
                })
                .toList();

        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("projectRoot", map.getProjectRoot());
        compact.put("languageStats", map.getLanguageStats());
        compact.put("totalFiles", map.getTotalFiles());
        compact.put("entryPoints", map.getEntryPoints());
        compact.put("keyFiles", keyFiles);
        compact.put("truncated", map.getFiles().size() > keyFiles.size());
        compact.put("note", "keyFiles 按角色与规模排序，仅用于交互式审计首轮提示；全量 Project-Map 已持久化到 data/projects/{projectId}/Project-Map.json");

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(compact);
    }

    private int scoreFileImportance(ProjectMap.FileInfo file) {
        int score = 0;
        List<String> roles = file.getRoles() == null ? List.of() : file.getRoles();
        if (roles.contains("controller"))
            score += 80;
        if (roles.contains("service"))
            score += 70;
        if (roles.contains("dao"))
            score += 60;
        if (roles.contains("config"))
            score += 40;
        if (roles.contains("other"))
            score += 10;
        if (file.getLineCount() > 400)
            score += 20;
        if (file.getLineCount() > 800)
            score += 20;
        return score;
    }

    private boolean isSupportedFile(Path path) {
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return false;
        }
        String ext = fileName.substring(dotIndex);
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    private boolean isNotInIgnoredDir(Path path) {
        String normalized = path.toString().replace('\\', '/');
        for (String ignored : IGNORED_DIRS) {
            String marker = "/" + ignored + "/";
            if (normalized.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    private ProjectMap.FileInfo toFileInfo(Path root, Path file) {
        try {
            String relativePath = root.relativize(file.toAbsolutePath()).toString().replace('\\', '/');
            String fileName = file.getFileName().toString();
            int dotIndex = fileName.lastIndexOf('.');
            if (dotIndex <= 0) {
                return null;
            }
            String ext = fileName.substring(dotIndex);
            String language = EXTENSION_TO_LANGUAGE.getOrDefault(ext, "Unknown");

            long lineCount;
            try (Stream<String> lines = Files.lines(file)) {
                lineCount = lines.count();
            }

            List<String> roles = inferRoles(relativePath);
            return new ProjectMap.FileInfo(relativePath, language, lineCount, roles);
        } catch (IOException e) {
            log.warn("Failed to read file info for {}: {}", file, e.getMessage());
            return null;
        }
    }

    private List<String> inferRoles(String relativePath) {
        String lower = relativePath.toLowerCase();
        List<String> roles = new ArrayList<>();
        if (lower.contains("controller")) {
            roles.add("controller");
        }
        if (lower.contains("service")) {
            roles.add("service");
        }
        if (lower.contains("repository") || lower.contains("dao")) {
            roles.add("dao");
        }
        if (lower.contains("config")) {
            roles.add("config");
        }
        if (lower.contains("test") || lower.contains("spec")) {
            roles.add("test");
        }
        if (roles.isEmpty()) {
            roles.add("other");
        }
        return roles;
    }

    private boolean looksLikeEntryPoint(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith("application.java")
                || lower.endsWith("main.py")
                || lower.endsWith("main.go")
                || lower.endsWith("main.cpp");
    }
}
