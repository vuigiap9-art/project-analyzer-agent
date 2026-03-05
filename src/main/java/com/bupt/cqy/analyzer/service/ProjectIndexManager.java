package com.bupt.cqy.analyzer.service;

import com.bupt.cqy.analyzer.model.CodeSnippet;
import com.bupt.cqy.analyzer.model.ProjectMap;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectIndexManager {

    private static final String VECTOR_STORE_FILE = "vector-store.json";
    private static final String SEGMENTS_FILE = "segments.json";
    private static final String META_FILE = "meta.json";
    private static final String BLUEPRINT_FILE = "blueprint.md";
    private static final String PROJECT_MAP_FILE = "Project-Map.json";
    private static final String CHAT_MEMORY_FILE = "chat-memory.json";

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
        return Files.exists(dir.resolve(VECTOR_STORE_FILE))
            && Files.exists(dir.resolve(SEGMENTS_FILE))
            && Files.exists(dir.resolve(META_FILE));
    }

    public LoadedProject load(String projectId) {
        return cache.computeIfAbsent(projectId, id -> {
            try {
                Path dir = projectDir(id);
                Path storePath = dir.resolve(VECTOR_STORE_FILE);
                Path segmentsPath = dir.resolve(SEGMENTS_FILE);
                Path blueprintPath = dir.resolve(BLUEPRINT_FILE);
                Path metaPath = dir.resolve(META_FILE);

                if (!Files.exists(storePath) || !Files.exists(segmentsPath) || !Files.exists(metaPath)) {
                    throw new IllegalStateException("项目索引不存在或不完整: " + id);
                }

                InMemoryEmbeddingStore<TextSegment> store = InMemoryEmbeddingStore.fromFile(storePath);
                List<TextSegment> segments = new ArrayList<>(readSegments(segmentsPath));
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
            store.serializeToFile(dir.resolve(VECTOR_STORE_FILE));
            writeSegments(dir.resolve(SEGMENTS_FILE), segments);
            Files.writeString(dir.resolve(BLUEPRINT_FILE), auditReport);

            ProjectMeta meta = new ProjectMeta(
                    projectId,
                    Path.of(projectPath).toAbsolutePath().normalize().toString(),
                    Instant.now().toString(),
                    snippets.size());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(META_FILE).toFile(), meta);

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
                    Path metaPath = dir.resolve(META_FILE);
                    if (!Files.exists(metaPath))
                        return;
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
        List<SegmentDto> dtos = objectMapper.readValue(segmentsPath.toFile(), new TypeReference<>() {
        });
        List<TextSegment> segments = new ArrayList<>(dtos.size());
        for (SegmentDto dto : dtos) {
            Metadata md = Metadata.from(dto.metadata());
            segments.add(TextSegment.from(dto.text(), md));
        }
        return segments;
    }

    public void persistProjectMap(String projectId, ProjectMap projectMap) {
        try {
            Path dir = projectDir(projectId);
            Files.createDirectories(dir);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(PROJECT_MAP_FILE).toFile(), projectMap);
        } catch (Exception e) {
            throw new RuntimeException("写入 Project-Map 失败: " + e.getMessage(), e);
        }
    }

    public String loadProjectMapJson(String projectId) {
        try {
            Path mapPath = projectDir(projectId).resolve(PROJECT_MAP_FILE);
            if (!Files.exists(mapPath)) {
                return "";
            }
            return Files.readString(mapPath);
        } catch (Exception e) {
            throw new RuntimeException("读取 Project-Map 失败: " + e.getMessage(), e);
        }
    }

    public synchronized void appendChatMemoryAndIndex(String projectId, String sessionId, String question, String answer) {
        try {
            LoadedProject project = load(projectId);
            Path dir = projectDir(projectId);
            Files.createDirectories(dir);

            List<ChatMemoryTurn> turns = readChatMemory(projectId);
            String normalizedSessionId = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
            int nextTurn = turns.stream().map(ChatMemoryTurn::turn).filter(Objects::nonNull).max(Comparator.naturalOrder())
                    .orElse(0) + 1;

            ChatMemoryTurn newTurn = new ChatMemoryTurn(
                    normalizedSessionId,
                    nextTurn,
                    Instant.now().toString(),
                    question,
                    answer);

            turns.add(newTurn);
            writeChatMemory(projectId, turns);

            List<TextSegment> memorySegments = buildMemorySegments(newTurn);
            if (!memorySegments.isEmpty()) {
                project.store().addAll(embeddingModel.embedAll(memorySegments).content(), memorySegments);
                project.segments().addAll(memorySegments);
                project.store().serializeToFile(dir.resolve(VECTOR_STORE_FILE));
                writeSegments(dir.resolve(SEGMENTS_FILE), project.segments());
            }
        } catch (Exception e) {
            throw new RuntimeException("追加对话记忆失败: " + e.getMessage(), e);
        }
    }

    public List<ChatMemoryTurn> readChatMemory(String projectId) {
        try {
            Path memoryPath = projectDir(projectId).resolve(CHAT_MEMORY_FILE);
            if (!Files.exists(memoryPath)) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(memoryPath.toFile(), new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("读取 chat-memory 失败，返回空记忆: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<ChatMemoryTurn> readRecentChatMemory(String projectId, String sessionId, int limit) {
        String normalizedSessionId = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        List<ChatMemoryTurn> sessionTurns = readChatMemory(projectId).stream()
                .filter(turn -> normalizedSessionId.equals(turn.sessionId()))
            .sorted(Comparator.comparingInt(turn -> turn.turn() == null ? 0 : turn.turn()))
                .toList();
        int fromIndex = Math.max(0, sessionTurns.size() - Math.max(1, limit));
        return sessionTurns.subList(fromIndex, sessionTurns.size());
    }

    private void writeChatMemory(String projectId, List<ChatMemoryTurn> turns) throws Exception {
        Path path = projectDir(projectId).resolve(CHAT_MEMORY_FILE);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), turns);
    }

    private List<TextSegment> buildMemorySegments(ChatMemoryTurn turn) {
        List<TextSegment> segments = new ArrayList<>(2);

        if (turn.question() != null && !turn.question().isBlank()) {
            Metadata userMd = Metadata.from("source", "chat-memory");
            userMd.put("role", "user");
            userMd.put("sessionId", turn.sessionId());
            userMd.put("turn", String.valueOf(turn.turn()));
            userMd.put("timestamp", turn.timestamp());
            segments.add(TextSegment.from("用户提问：" + turn.question().trim(), userMd));
        }

        if (turn.answer() != null && !turn.answer().isBlank()) {
            Metadata aiMd = Metadata.from("source", "chat-memory");
            aiMd.put("role", "ai");
            aiMd.put("sessionId", turn.sessionId());
            aiMd.put("turn", String.valueOf(turn.turn()));
            aiMd.put("timestamp", turn.timestamp());
            segments.add(TextSegment.from("AI答复：" + turn.answer().trim(), aiMd));
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
            List<TextSegment> segments) {
    }

        public record ChatMemoryTurn(String sessionId, Integer turn, String timestamp, String question, String answer) {
        }

    private record SegmentDto(String text, Map<String, Object> metadata) {
    }
}
