package com.bupt.cqy.analyzer.service;

import com.bupt.cqy.analyzer.model.CodeSnippet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Slf4j
@Service
public class CodeCrawlerService {

    private static final Set<String> IGNORED_DIRS = Set.of(
            "node_modules", ".git", "target", "bin", "build", ".idea", ".vscode", "dist", "out"
    );

    private static final Map<String, String> EXTENSION_TO_LANGUAGE = Map.of(
            ".java", "Java",
            ".py", "Python",
            ".cpp", "C++",
            ".go", "Go"
    );

    public List<CodeSnippet> scanProject(String rootPath) throws IOException, InterruptedException {
        List<CodeSnippet> snippets = new CopyOnWriteArrayList<>();
        Path root = Path.of(rootPath);

        if (!Files.exists(root)) {
            throw new IOException("Path does not exist: " + rootPath);
        }

        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .filter(this::isNotInIgnoredDir)
                    .filter(this::isSupportedFile)
                    .toList();

            log.info("Found {} code files to scan", files.size());

            ExecutorService executor = Executors.newCachedThreadPool();
            for (Path file : files) {
                executor.submit(() -> processFile(file, snippets));
            }
            executor.shutdown();
            if (!executor.awaitTermination(120, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate in time");
                executor.shutdownNow();
            }
        }

        log.info("Scan completed: {} snippets extracted", snippets.size());
        return snippets;
    }

    private void processFile(Path file, List<CodeSnippet> snippets) {
        try {
            String content = Files.readString(file);
            String extension = getFileExtension(file);
            String language = EXTENSION_TO_LANGUAGE.get(extension);

            snippets.add(new CodeSnippet(file.toString(), language, content));
        } catch (IOException e) {
            log.warn("Failed to read file: {}", file, e);
        }
    }

    private boolean isNotInIgnoredDir(Path path) {
        return path.getNameCount() > 0 &&
               path.toString().split("/|\\\\").length > 0 &&
               IGNORED_DIRS.stream().noneMatch(ignored -> path.toString().contains("/" + ignored + "/") || path.toString().contains("\\" + ignored + "\\"));
    }

    private boolean isSupportedFile(Path path) {
        String extension = getFileExtension(path);
        return EXTENSION_TO_LANGUAGE.containsKey(extension);
    }

    private String getFileExtension(Path path) {
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(dotIndex) : "";
    }
}
