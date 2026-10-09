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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
    /** 조각이 속한 절의 제목 경로. 검색 결과를 눈으로 확인할 때 쓴다. */
    public static final String META_HEADING = "heading";

    /** 마크다운 제목 줄. 예: "### 연계통합" */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    /** 마크다운 이미지 링크. 예: ![압축해제 완료](./images/decompress.jpg) */
    private static final Pattern IMAGE_LINK = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    /** 이미지 링크를 걷어낸 뒤 공백만 남은 줄 */
    private static final Pattern BLANK_LINE = Pattern.compile("(?m)^[ \\t]+$");
    /** 빈 줄이 겹친 자리 */
    private static final Pattern MULTI_NEWLINE = Pattern.compile("\\n{3,}");

    /** 조각 맨 끝에 내용 없이 남은 목록 번호. 예: "…메시지 창이 뜬다.\n\n10." */
    private static final Pattern TRAILING_LIST_MARKER = Pattern.compile("(?:\\R|^)[ \\t]*\\d+\\.[ \\t]*$");

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

        // 폴더가 기준이다. 폴더에서 사라진 파일을 먼저 걷어낸 뒤 적재한다.
        List<FileIngestResult> results = new ArrayList<>(removeOrphans(targets));
        for (Path file : targets) {
            results.add(ingestOne(file, force));
        }
        return results;
    }

    /**
     * 적재 기록에는 있는데 폴더에는 없는 파일을 정리한다.
     * 이게 없으면 문서를 폴더에서 빼도 청크가 DB에 남아 검색 결과에 끼어든다.
     * 대상 파일이 하나도 없으면 ingestAll이 먼저 빠져나가므로, 경로를 잘못 잡아 전부 지우는 일은 없다.
     */
    private List<FileIngestResult> removeOrphans(List<Path> targets) {
        Set<String> present = targets.stream()
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toSet());

        List<FileIngestResult> removed = new ArrayList<>();
        for (IngestedFileRepository.IngestedFile ingested : ingestedFiles.findAll()) {
            if (present.contains(ingested.fileName())) {
                continue;
            }
            forget(ingested.fileName());
            removed.add(new FileIngestResult(ingested.fileName(), Status.REMOVED, 0));
        }
        return removed;
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

    /** 파일 하나를 읽어 절 단위로 나눈 뒤 청크로 쪼개고, 각 청크에 메타데이터를 붙인다. */
    private List<Document> readAndSplit(Path file, String fileName, String hash) {
        TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(file));
        List<Document> rawDocuments = reader.read();

        List<Document> enriched = new ArrayList<>();
        int chunkIndex = 0;
        for (Document raw : rawDocuments) {
            String text = stripImageLinks(raw.getText());
            if (text == null || text.isBlank()) {
                continue;
            }
            for (Section section : splitByHeading(text)) {
                List<Document> parts =
                        this.splitter.apply(List.of(new Document(section.body(), raw.getMetadata())));
                for (Document part : parts) {
                    String body = part.getText();
                    if (body == null || body.isBlank()) {
                        continue;
                    }
                    body = stripTrailingListMarker(body);
                    if (body.isBlank()) {
                        continue;
                    }
                    // 리더가 넣어준 메타데이터를 살리되, 출처 키는 우리가 확정적으로 덮어쓴다.
                    Map<String, Object> metadata = new HashMap<>(part.getMetadata());
                    metadata.put(META_SOURCE, fileName);
                    metadata.put(META_FILE_HASH, hash);
                    metadata.put(META_CHUNK_INDEX, chunkIndex++);
                    metadata.put(META_HEADING, section.path());
                    enriched.add(new Document(withHeading(section.path(), body), metadata));
                }
            }
        }
        return enriched;
    }

    /**
     * 조각 맨 앞에 제목 경로를 붙인다. 목록이 조각 경계에서 잘리면 뒷조각이 어느 절
     * 소속인지 알 수 없어, 모델이 항목을 빠뜨리거나 엉뚱한 절에 붙이는 문제가 있었다.
     */
    private String withHeading(String path, String body) {
        return path.isEmpty() ? body : "[" + path + "]\n" + body;
    }

    /**
     * 마크다운 제목으로 먼저 끊는다. 짧은 절은 통째로 한 조각이 되므로 세 항목짜리
     * 목록이 둘로 갈리지 않는다. 제목이 없는 문서(PDF 등)는 경로가 빈 섹션 하나가 된다.
     */
    private List<Section> splitByHeading(String text) {
        List<Section> sections = new ArrayList<>();
        String[] titles = new String[7];
        StringBuilder body = new StringBuilder();
        String path = "";

        for (String line : text.split("\\R", -1)) {
            Matcher matcher = HEADING.matcher(line);
            if (!matcher.matches()) {
                body.append(line).append('\n');
                continue;
            }
            addSection(sections, path, body);
            body.setLength(0);
            int level = matcher.group(1).length();
            titles[level] = matcher.group(2).strip();
            for (int deeper = level + 1; deeper < titles.length; deeper++) {
                titles[deeper] = null;
            }
            path = headingPath(titles);
        }
        addSection(sections, path, body);
        return sections;
    }

    /** 모아둔 본문에 내용이 있을 때만 섹션으로 만들어 담는다. */
    private void addSection(List<Section> sections, String path, StringBuilder body) {
        String text = body.toString().strip();
        if (!text.isEmpty()) {
            sections.add(new Section(path, text));
        }
    }

    /**
     * 마크다운 이미지 링크를 걷어낸다. 그림 파일은 적재하지 않으므로 이 줄은 조각에
     * 아무 정보도 주지 못하면서 토큰만 먹는다. alt 텍스트도 같이 지운다 — 191개 중
     * 37개가 `![image]`이고 나머지도 바로 앞 절차 문장과 겹치는 UI 라벨이다.
     *
     * 개발환경·공통컴포넌트 문서는 파일당 10~19개가 들어 있다. "게시판 설치" 질문의
     * 조각에서는 본문 3,281자 중 898자(27.4%)가 이 링크였고, 걷어내니 프롬프트가
     * 1,749 → 1,388토큰(-20.6%)으로 줄었다. prefill은 토큰 수에 비례하므로 그만큼
     * 첫 글자까지의 시간이 준다. 조각이 링크 한가운데에서 잘려 "!" 한 글자만 남던
     * 것도 사라진다. 근거는 dev_docs/7차_개선.md.
     */
    private String stripImageLinks(String text) {
        if (text == null) {
            return null;
        }
        String stripped = IMAGE_LINK.matcher(text).replaceAll("");
        stripped = BLANK_LINE.matcher(stripped).replaceAll("");
        return MULTI_NEWLINE.matcher(stripped).replaceAll("\n\n");
    }

    /**
     * 조각 끝에 번호만 외톨이로 남은 것을 뗀다. 250토큰 경계가 "10." 바로 뒤에 떨어지면
     * 그 항목의 본문은 다음 조각으로 가고 번호만 남는다. 내용이 없으므로 화면의 근거
     * 모달에도 "10."만 덩그러니 보이고 프롬프트에서는 토큰만 먹는다.
     *
     * 이미지 링크와 달리 원문에는 문제가 없고 자르는 자리가 만드는 것이라, 걷어내는
     * 시점도 파일을 읽을 때가 아니라 조각으로 쪼갠 뒤다. 근거는 dev_docs/7차_개선.md.
     */
    private String stripTrailingListMarker(String body) {
        return TRAILING_LIST_MARKER.matcher(body.stripTrailing()).replaceAll("");
    }

    private String headingPath(String[] titles) {
        StringBuilder path = new StringBuilder();
        for (String title : titles) {
            if (title == null) {
                continue;
            }
            if (!path.isEmpty()) {
                path.append(" > ");
            }
            path.append(title);
        }
        return path.toString();
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

    /** 제목 경로와 그 아래 본문. 제목이 없는 문서는 경로가 빈 섹션 하나가 된다. */
    private record Section(String path, String body) {
    }

    public enum Status {
        /** 새로 적재됨 */ ADDED,
        /** 내용이 바뀌어 재적재됨 */ UPDATED,
        /** 변경 없어 건너뜀 */ SKIPPED,
        /** 텍스트 추출 실패 */ EMPTY,
        /** 폴더에서 사라져 청크와 기록을 지움 */ REMOVED
    }

    public record FileIngestResult(String fileName, Status status, int chunkCount) {
    }
}