package com.bupt.cqy.analyzer.service;

import com.bupt.cqy.analyzer.model.Document;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
public class FileCrawlerService {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".java", ".py", ".cpp", ".js");

    public List<Document> crawl(String rootDir) throws IOException, InterruptedException {
        List<Document> documents = new CopyOnWriteArrayList<>();

        try (Stream<Path> paths = Files.walk(Path.of(rootDir))) {
            List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> SUPPORTED_EXTENSIONS.stream().anyMatch(p.toString()::endsWith))
                    .toList();

            ExecutorService executor = Executors.newCachedThreadPool();
            for (Path file : files) {
                executor.submit(() -> {
                    try {
                        String content = Files.readString(file);
                        String ext = file.toString().substring(file.toString().lastIndexOf('.'));
                        documents.add(new Document(file.toString(), content, ext));
                    } catch (IOException e) {
                        // skip unreadable files
                    }
                });
            }
            executor.shutdown();
            executor.awaitTermination(60, TimeUnit.SECONDS);
        }

        return documents;
    }
}
