package com.example.rag.api;

import com.example.rag.ingest.IngestService;
import com.example.rag.ingest.IngestedFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class RagController {

    /**
     * 답변 규칙. 긍정 지시를 앞에, "문서에서 찾을 수 없습니다" 대체 응답을 맨 뒤에 둔다.
     * 작은 모델은 규칙이 늘면 뒤쪽 지시를 흘리므로 규칙 수는 최소로 유지한다.
     * 질문의 틀린 전제를 바로잡는 규칙은 시스템 프롬프트와 질문 바로 뒤 양쪽에서 시도했으나
     * exaone3.5:2.4b에서는 둘 다 듣지 않아 넣지 않는다. 근거는 dev_docs/평가_질문.md 10번.
     */
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

    /** 스트리밍의 제한 시간. 기준선 측정의 최장 답변이 201초라 세 배로 둔다. */
    private static final long STREAM_TIMEOUT_MILLIS = 600_000L;

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final IngestService ingestService;
    private final int topK;
    private final double similarityThreshold;

    public RagController(ChatClient.Builder chatClientBuilder,
                         VectorStore vectorStore,
                         IngestService ingestService,
                         @Value("${rag.top-k:8}") int topK,
                         @Value("${rag.similarity-threshold:0.40}") double similarityThreshold) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.ingestService = ingestService;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    /**
     * 검색만 수행. 검색 품질을 눈으로 확인할 때 쓴다.
     *
     * threshold·topK를 넘기면 설정값 대신 그 값으로 조회한다. 임계값은 코퍼스에 딸린 값이라
     * 문서를 추가할 때마다 무관한 질문의 점수를 다시 재야 하는데, 그러려면 임계값 0으로 볼 수
     * 있어야 한다. yml을 고쳐 재시작하는 대신 조회 시점에만 덮어쓴다(설정은 그대로 둔다).
     * 답변(/ask)에는 두지 않는다. 답변 측정은 사용자가 쓰는 설정과 같은 조건이어야 한다.
     */
    @PostMapping("/search")
    public List<SourceRef> search(@RequestBody AskRequest request,
                                  @RequestParam(name = "threshold", required = false) Double threshold,
                                  @RequestParam(name = "topK", required = false) Integer topK) {
        return toSourceRefs(retrieve(request.question(),
                topK == null ? this.topK : topK,
                threshold == null ? this.similarityThreshold : threshold));
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
     * 검색 + 스트리밍 답변. 근거를 먼저 보내고 답변은 생성되는 대로 흘려보낸다.
     * CPU 추론은 답변 하나에 40~170초가 걸리고 GPU가 없어 실제 시간을 줄일 수 없으므로,
     * 글자가 먼저 나오게 해 체감을 바꾼다. 근거는 project_status.md §4.
     *
     * GET인 이유는 화면이 쓰는 EventSource가 GET만 지원하기 때문이다. 빌드 도구 없는
     * 화면에서는 이게 가장 단순하다. 한글 질문은 화면에서 encodeURIComponent로 감싼다.
     * /ask는 그대로 남긴다 — 측정 보고서가 /ask 기준이라 바꾸면 이전 회차와 비교가 끊긴다.
     *
     * 이벤트는 네 가지다.
     *   sources  근거 조각 목록(배열). 0건이면 LLM을 부르지 않는다.
     *   delta    답변 조각 하나.
     *   done     답변 전문. 화면이 마지막에 한 번 더 그려 조각을 이어 붙인 것과 어긋나지 않게 한다.
     *   fail     생성 중 오류. 이름을 error로 하면 EventSource의 연결 오류 이벤트와 섞인다.
     */
    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestParam("question") String question) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        List<Document> documents = retrieve(question);

        if (!send(emitter, "sources", toSourceRefs(documents))) {
            return emitter;
        }

        // 임계값을 넘는 조각이 없으면 답은 정해져 있다. LLM을 부르지 않아 수십 초를 아낀다.
        if (documents.isEmpty()) {
            String answer = "문서에서 찾을 수 없습니다.";
            if (send(emitter, "delta", new StreamText(answer))) {
                send(emitter, "done", new StreamText(answer));
            }
            emitter.complete();
            return emitter;
        }

        // 조각을 이어 붙여 done에 전문을 함께 싣는다. 신호가 순서대로 오므로 동기화는 필요 없다.
        StringBuilder answer = new StringBuilder();
        Disposable subscription = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(buildUserMessage(question, documents))
                .stream()
                .content()
                .subscribe(
                        chunk -> {
                            answer.append(chunk);
                            send(emitter, "delta", new StreamText(chunk));
                        },
                        error -> {
                            log.warn("스트리밍 답변 생성이 실패했습니다: {}", error.toString());
                            send(emitter, "fail", new StreamText(messageOf(error)));
                            // completeWithError로 끊으면 EventSource가 재연결해 추론을 또 태운다.
                            emitter.complete();
                        },
                        () -> {
                            send(emitter, "done", new StreamText(answer.toString()));
                            emitter.complete();
                        });

        // 화면이 닫히거나 제한 시간이 지나면 생성을 멈춘다. 안 끊으면 Ollama가 끝까지 계산한다.
        emitter.onCompletion(subscription::dispose);
        emitter.onError(error -> subscription.dispose());
        emitter.onTimeout(() -> {
            subscription.dispose();
            emitter.complete();
        });
        return emitter;
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
        return retrieve(question, topK, similarityThreshold);
    }

    private List<Document> retrieve(String question, int topK, double similarityThreshold) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build();
        // similaritySearch는 구현체에 따라 null을 돌려줄 수 있어 방어한다.
        return Optional.ofNullable(vectorStore.similaritySearch(searchRequest)).orElse(List.of());
    }

    /**
     * SSE 이벤트 하나를 보낸다. 보냈으면 true.
     * 화면이 먼저 닫히면 send가 실패하는데 이건 오류가 아니므로 조용히 스트림을 닫는다.
     */
    private boolean send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            emitter.complete();
            return false;
        }
    }

    /** 화면에 보여줄 오류 문구. 메시지가 없는 예외도 있어 타입 이름으로 대신한다. */
    private String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? "답변 생성이 실패했습니다 (" + error.getClass().getSimpleName() + ")"
                : message;
    }

    private String buildUserMessage(String question, List<Document> documents) {
        // 조각은 점수 높은 순으로 넣는다. 1위를 질문 바로 앞에 두는 역순도 재봤지만
        // 10번(전제 교정)은 그대로였고 3·6번이 나빠져 되돌렸다. 근거는 dev_docs/평가_질문.md 5차.
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
            String text = textOf(document);
            refs.add(new SourceRef(
                    i + 1,
                    sourceOf(document),
                    document.getScore(),
                    excerpt(text),
                    text));
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

    /**
     * 스트리밍 이벤트의 본문. delta는 답변 조각 하나, done은 답변 전문, fail은 오류 메시지다.
     * 답변에는 줄바꿈이 있어 그대로 보내면 SSE의 data 줄이 끊긴다. JSON으로 감싸면 줄바꿈이
     * \n으로 이스케이프돼 한 줄로 간다.
     */
    public record StreamText(String text) {
    }

    /**
     * 답변의 근거가 된 청크 하나. index는 프롬프트의 [1], [2] 번호와 일치한다.
     * excerpt는 목록에 줄여 보여줄 용도, text는 화면에서 전문을 펼칠 용도다.
     */
    public record SourceRef(int index, String source, Double score, String excerpt, String text) {
    }
}