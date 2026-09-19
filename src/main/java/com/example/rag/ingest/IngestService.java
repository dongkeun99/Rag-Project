package com.example.rag.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 폴더 안의 문서를 읽어 청킹 → 임베딩 → pgvector 적재.
 * 같은 파일을 다시 넣어도 청크가 중복 적재되지 않는다.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    /** Tika가 처리할 수 있고, 실제로 쓸 만한 확장자만 추린 목록. */
    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of("pdf", "doc", "docx", "ppt", "pptx", "html", "htm", "txt", "md");

    /** 벡터스토어 메타데이터에 넣을 키. 검색 결과의 출처 표시와 파일 단위 삭제에 쓰인다. */
    public static final String META_SOURCE = "source";
    public static final String META_FILE_HASH = "fileHash";
    public static final String META_CHUNK_INDEX = "chunkIndex";

    private final VectorStore vectorStore;
    private final IngestedFileRepository ingestedFiles;
    private final Path docsPath;
    private final TokenTextSplitter splitter;

    public IngestService(VectorStore vectorStore,
                         IngestedFileRepository ingestedFiles,
                         @Value("${rag.docs-path}") String docsPath,
                         @Value("${rag.chunk-size:250}") int chunkSize,
                         @Value("${rag.min-chunk-size-chars:120}") int minChunkSizeChars) {
        this.vectorStore = vectorStore;
        this.ingestedFiles = ingestedFiles;
        this.docsPath = Path.of(docsPath);
        // 기본값(800토큰/350자)은 한글 문서에 너무 커서 청크 하나에 여러 주제가 섞인다.
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(minChunkSizeChars)
                .withMinChunkLengthToEmbed(30)
                .build();
        log.info("청킹 설정: chunkSize={} 토큰, minChunkSizeChars={}", chunkSize, minChunkSizeChars);
    }

    /**
     * 폴더 전체를 훑어 새 파일과 바뀐 파일만 적재한다.
     *
     * @param force true면 내용이 그대로여도 전부 다시 적재한다.
     *              청킹 설정을 바꿔 실험할 때 쓴다.
     */
    public List<FileIngestResult> ingestAll(boolean force) throws IOException {
        if (!Files.isDirectory(docsPath)) {
            throw new IllegalStateException("문서 폴더를 찾을 수 없습니다: " + docsPath.toAbsolutePath());
        }

        List<Path> targets;
        try (Stream<Path> files = Files.list(docsPath)) {
            targets = files.filter(Files::isRegularFile)
                    .filter(this::isSupported)
                    .sorted()
                    .toList();
        }

        if (targets.isEmpty()) {
            log.warn("적재할 문서가 없습니다: {}", docsPath.toAbsolutePath());
            return List.of();
        }

        List<FileIngestResult> results = new ArrayList<>(targets.size());
        for (Path file : targets) {
            results.add(ingestOne(file, force));
        }
        return results;
    }

    private FileIngestResult ingestOne(Path file, boolean force) throws IOException {
        String fileName = file.getFileName().toString();
        String hash = sha256(file);

        Optional<String> storedHash = ingestedFiles.findHash(fileName);

        // 1) 같은 파일 + 같은 내용 → 아무것도 하지 않는다 (force면 무시하고 진행)
        if (!force && storedHash.filter(hash::equals).isPresent()) {
            log.info("변경 없음, 건너뜀: {}", fileName);
            return new FileIngestResult(fileName, Status.SKIPPED, 0);
        }

        // 2) 이미 적재된 적 있는 파일이면 기존 청크를 먼저 지운다 (중복 방지)
        boolean isUpdate = storedHash.isPresent();
        if (isUpdate) {
            log.info("기존 청크를 삭제하고 다시 적재합니다: {}", fileName);
            deleteBySource(fileName);
        }

        List<Document> chunks = readAndSplit(file, fileName, hash);
        if (chunks.isEmpty()) {
            log.warn("텍스트를 추출하지 못했습니다(이미지 PDF일 수 있음): {}", fileName);
            return new FileIngestResult(fileName, Status.EMPTY, 0);
        }

        vectorStore.add(chunks);
        ingestedFiles.upsert(fileName, hash, chunks.size());
        log.info("적재 완료: {} ({} 청크)", fileName, chunks.size());

        return new FileIngestResult(fileName, isUpdate ? Status.UPDATED : Status.ADDED, chunks.size());
    }

    /** 파일 하나를 읽어 청크로 쪼개고, 각 청크에 출처 메타데이터를 붙인다. */
    private List<Document> readAndSplit(Path file, String fileName, String hash) {
        TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(file));
        List<Document> rawDocuments = reader.read();
        List<Document> chunks = this.splitter.apply(rawDocuments);

        List<Document> enriched = new ArrayList<>(chunks.size());
        int chunkIndex = 0;
        for (Document chunk : chunks) {
            String text = chunk.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            // 리더가 넣어준 메타데이터를 살리되, 출처 키는 우리가 확정적으로 덮어쓴다.
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put(META_SOURCE, fileName);
            metadata.put(META_FILE_HASH, hash);
            metadata.put(META_CHUNK_INDEX, chunkIndex++);
            enriched.add(new Document(text, metadata));
        }
        return enriched;
    }

    /** 특정 파일에서 나온 청크 전부 삭제. source 메타데이터로 걸러낸다. */
    public void deleteBySource(String fileName) {
        vectorStore.delete(new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key(META_SOURCE),
                new Filter.Value(fileName)));
    }

    /** 파일을 목록에서 완전히 제거(청크 + 적재 기록). */
    public void forget(String fileName) {
        deleteBySource(fileName);
        ingestedFiles.delete(fileName);
        log.info("삭제 완료: {}", fileName);
    }

    public List<IngestedFileRepository.IngestedFile> listIngested() {
        return ingestedFiles.findAll();
    }

    private boolean isSupported(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot > 0 && SUPPORTED_EXTENSIONS.contains(name.substring(dot + 1));
    }

    /** 파일 내용이 바뀌었는지 판단하는 기준값. 수정 날짜는 내용이 같아도 바뀌므로 해시를 쓴다. */
    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }

    public enum Status {
        /** 새로 적재됨 */ ADDED,
        /** 내용이 바뀌어 재적재됨 */ UPDATED,
        /** 변경 없어 건너뜀 */ SKIPPED,
        /** 텍스트 추출 실패 */ EMPTY
    }

    public record FileIngestResult(String fileName, Status status, int chunkCount) {
    }
}