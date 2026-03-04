package com.bupt.cqy.analyzer.service;

import com.bupt.cqy.analyzer.model.CodeSnippet;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectIndexManager {

    private final EmbeddingModel embeddingModel;
    private final IndexService indexService;
    private final ObjectMapper objectMapper;

    @Value("${rag.projects.dir:./data/projects}")
    private String projectsDir;

    private final ConcurrentHashMap<String, LoadedProject> cache = new ConcurrentHashMap<>();

    public String projectIdForPath(String projectPath) {
        try {
            String normalized = Path.of(projectPath).toAbsolutePath().normalize().toString();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            // 取前 12 bytes（24 hex），足够短且碰撞概率极低
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception e) {
            // 降级：直接用 hashCode
            return Integer.toHexString(projectPath.hashCode());
        }
    }

    public Path projectDir(String projectId) {
        return Path.of(projectsDir, projectId);
    }

    public boolean isIndexed(String projectId) {
        Path dir = projectDir(projectId);
        return Files.exists(dir.resolve("vector-store.json"))
                && Files.exists(dir.resolve("segments.json"))
                && Files.exists(dir.resolve("meta.json"));
    }

    public LoadedProject load(String projectId) {
        return cache.computeIfAbsent(projectId, id -> {
            try {
                Path dir = projectDir(id);
                Path storePath = dir.resolve("vector-store.json");
                Path segmentsPath = dir.resolve("segments.json");
                Path blueprintPath = dir.resolve("blueprint.md");
                Path metaPath = dir.resolve("meta.json");

                if (!Files.exists(storePath) || !Files.exists(segmentsPath) || !Files.exists(metaPath)) {
                    throw new IllegalStateException("项目索引不存在或不完整: " + id);
                }

                InMemoryEmbeddingStore<TextSegment> store = InMemoryEmbeddingStore.fromFile(storePath);
                List<TextSegment> segments = readSegments(segmentsPath);
                String blueprint = Files.exists(blueprintPath) ? Files.readString(blueprintPath) : "";
                ProjectMeta meta = objectMapper.readValue(metaPath.toFile(), ProjectMeta.class);

                log.info("加载项目索引完成: {} ({})", id, meta.rootPath());
                return new LoadedProject(id, meta, blueprint, store, segments);
            } catch (Exception e) {
                throw new RuntimeException("加载项目索引失败: " + projectId + " - " + e.getMessage(), e);
            }
        });
    }

    public LoadedProject indexAndPersist(String projectPath, String auditReport, List<CodeSnippet> snippets) {
        String projectId = projectIdForPath(projectPath);
        try {
            Path dir = projectDir(projectId);
            Files.createDirectories(dir);

            // 1) 构建 segments
            List<TextSegment> segments = indexService.buildSegments(auditReport, snippets);

            // 2) 建 store（每个项目独立一个文件）
            InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
            store.addAll(embeddingModel.embedAll(segments).content(), segments);

            // 3) 持久化
            store.serializeToFile(dir.resolve("vector-store.json"));
            writeSegments(dir.resolve("segments.json"), segments);
            Files.writeString(dir.resolve("blueprint.md"), auditReport);

            ProjectMeta meta = new ProjectMeta(
                    projectId,
                    Path.of(projectPath).toAbsolutePath().normalize().toString(),
                    Instant.now().toString(),
                    snippets.size()
            );
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("meta.json").toFile(), meta);

            LoadedProject loaded = new LoadedProject(projectId, meta, auditReport, store, segments);
            cache.put(projectId, loaded);
            log.info("项目索引已持久化: {} -> {}", projectId, dir.toAbsolutePath());
            return loaded;
        } catch (Exception e) {
            throw new RuntimeException("项目索引持久化失败: " + e.getMessage(), e);
        }
    }

    public List<ProjectMeta> listProjects() {
        try {
            Path base = Path.of(projectsDir);
            if (!Files.exists(base) || !Files.isDirectory(base)) {
                return List.of();
            }

            List<ProjectMeta> result = new ArrayList<>();
            try (var stream = Files.list(base)) {
                stream.filter(Files::isDirectory).forEach(dir -> {
                    Path metaPath = dir.resolve("meta.json");
                    if (!Files.exists(metaPath)) return;
                    try {
                        ProjectMeta meta = objectMapper.readValue(metaPath.toFile(), ProjectMeta.class);
                        result.add(meta);
                    } catch (Exception ignored) {
                        // ignore broken meta
                    }
                });
            }
            return result;
        } catch (Exception e) {
            log.warn("列出项目失败: {}", e.getMessage());
            return List.of();
        }
    }

    private void writeSegments(Path segmentsPath, List<TextSegment> segments) throws Exception {
        List<SegmentDto> dtos = segments.stream()
                .map(seg -> new SegmentDto(seg.text(), seg.metadata().toMap()))
                .toList();
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(segmentsPath.toFile(), dtos);
    }

    private List<TextSegment> readSegments(Path segmentsPath) throws Exception {
        List<SegmentDto> dtos = objectMapper.readValue(segmentsPath.toFile(), new TypeReference<>() {});
        List<TextSegment> segments = new ArrayList<>(dtos.size());
        for (SegmentDto dto : dtos) {
            Metadata md = Metadata.from(dto.metadata());
            segments.add(TextSegment.from(dto.text(), md));
        }
        return segments;
    }

    public record ProjectMeta(String projectId, String rootPath, String indexedAt, int filesScanned) {
    }

    public record LoadedProject(
            String projectId,
            ProjectMeta meta,
            String blueprint,
            InMemoryEmbeddingStore<TextSegment> store,
            List<TextSegment> segments
    ) {
    }

    private record SegmentDto(String text, Map<String, Object> metadata) {
    }
}

