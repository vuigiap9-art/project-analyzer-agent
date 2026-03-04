package com.bupt.cqy.analyzer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
public class ProjectSummarizerService {

    public void saveBlueprint(String auditReport, String outputPath) throws IOException {
        Path path = Paths.get(outputPath, "Project-Blueprint.md");
        Files.writeString(path, auditReport);
        log.info("Blueprint saved to: {}", path.toAbsolutePath());
    }
}
