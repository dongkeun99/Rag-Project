package com.example.rag.api;

import com.example.rag.ingest.IngestService;
import com.example.rag.ingest.IngestedFileRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class RagController {

    private static final String SYSTEM_PROMPT = """
            아래 문서 조각을 근거로 질문에 한국어로 답하세요.

            - 문서 조각에 있는 내용으로 답하고, 없는 내용은 지어내지 마세요.
            - 문서에 쓰인 용어를 그대로 사용하세요.
            - 목록을 묻는 질문이면 문서에 있는 항목을 빠짐없이 나열하세요.
            - 답만 간결하게 쓰세요. 문서에 대한 설명, 사과, "문서에 따르면" 같은
              머리말은 붙이지 마세요.
            - 어느 조각에도 근거가 없을 때만 "문서에서 찾을 수 없습니다"라고 답하세요.
            """;

    private static final int EXCERPT_LENGTH = 200;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final IngestService ingestService;
    private final int topK;
    private final double similarityThreshold;

    public RagController(ChatClient.Builder chatClientBuilder,
                         VectorStore vectorStore,
                         IngestService ingestService,
                         @Value("${rag.top-k:3}") int topK,
                         @Value("${rag.similarity-threshold:0.40}") double similarityThreshold) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.ingestService = ingestService;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    /** 검색만 수행. 검색 품질을 눈으로 확인할 때 쓴다. */
    @PostMapping("/search")
    public List<SourceRef> search(@RequestBody AskRequest request) {
        return toSourceRefs(retrieve(request.question()));
    }

    /** 검색 + 답변 생성. 답변과 함께 근거 조각을 돌려준다. */
    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        List<Document> documents = retrieve(request.question());

        if (documents.isEmpty()) {
            return new AskResponse("문서에서 찾을 수 없습니다.", List.of());
        }

        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(buildUserMessage(request.question(), documents))
                .call()
                .content();

        return new AskResponse(answer, toSourceRefs(documents));
    }

    /**
     * 폴더를 훑어 새 문서와 바뀐 문서만 적재. 여러 번 호출해도 중복되지 않는다.
     * ?force=true 를 붙이면 내용이 그대로여도 전부 다시 적재한다(청킹 설정 실험용).
     */
    @PostMapping("/ingest")
    public List<IngestService.FileIngestResult> ingest(
            @RequestParam(name = "force", defaultValue = "false") boolean force) throws IOException {
        return ingestService.ingestAll(force);
    }

    /** 화면이 임계값과 top-k를 표시할 수 있도록 현재 설정을 알려준다. */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("topK", topK, "similarityThreshold", similarityThreshold);
    }

    /** 현재 적재돼 있는 파일 목록. */
    @GetMapping("/ingested")
    public List<IngestedFileRepository.IngestedFile> ingested() {
        return ingestService.listIngested();
    }

    private List<Document> retrieve(String question) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build();
        // similaritySearch는 구현체에 따라 null을 돌려줄 수 있어 방어한다.
        return Optional.ofNullable(vectorStore.similaritySearch(searchRequest)).orElse(List.of());
    }

    private String buildUserMessage(String question, List<Document> documents) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            context.append("[").append(i + 1).append("] 출처: ").append(sourceOf(document)).append("\n")
                    .append(textOf(document)).append("\n\n");
        }
        return """
                # 문서 조각
                %s

                # 질문
                %s
                """.formatted(context.toString().trim(), question);
    }

    private List<SourceRef> toSourceRefs(List<Document> documents) {
        List<SourceRef> refs = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            refs.add(new SourceRef(
                    i + 1,
                    sourceOf(document),
                    document.getScore(),
                    excerpt(textOf(document))));
        }
        return refs;
    }

    private String sourceOf(Document document) {
        Object source = document.getMetadata().get("source");
        return source == null ? "알 수 없음" : source.toString();
    }

    private String textOf(Document document) {
        String text = document.getText();
        return text == null ? "" : text;
    }

    private String excerpt(String text) {
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= EXCERPT_LENGTH
                ? flattened
                : flattened.substring(0, EXCERPT_LENGTH) + "…";
    }

    public record AskRequest(String question) {
    }

    public record AskResponse(String answer, List<SourceRef> sources) {
    }

    /** 답변의 근거가 된 청크 하나. index는 프롬프트의 [1], [2] 번호와 일치한다. */
    public record SourceRef(int index, String source, Double score, String excerpt) {
    }
}