package com.example.rag.eval;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 떠 있는 앱을 HTTP로 때리는 측정용 클라이언트.
 * 스프링 컨텍스트를 띄우지 않고 실제 엔드포인트를 쓴다. 측정은 사용자가 쓰는 경로와 같아야 한다.
 */
class RagApi {

    private static final ParameterizedTypeReference<List<Hit>> HIT_LIST = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<Ingested>> INGESTED_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient client;

    RagApi(String baseUrl, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        // CPU 추론이면 답변 하나에 수 분이 걸린다. 읽기 제한을 넉넉히 둔다.
        factory.setReadTimeout(readTimeout);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    Config config() {
        return client.get().uri("/api/config").retrieve().body(Config.class);
    }

    List<Ingested> ingested() {
        return client.get().uri("/api/ingested").retrieve().body(INGESTED_LIST);
    }

    /** 설정된 임계값으로 검색. 사용자가 질문할 때와 같은 조건이다. */
    List<Hit> search(String question) {
        return client.post().uri("/api/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", question))
                .retrieve().body(HIT_LIST);
    }

    /**
     * 임계값을 덮어써 검색. 무관한 질문의 최고 점수는 임계값 0으로 조회해야 보인다.
     * 설정값은 건드리지 않으므로 측정 중에 앱의 동작이 흔들리지 않는다.
     */
    List<Hit> search(String question, double threshold) {
        return client.post().uri("/api/search?threshold={threshold}", threshold)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", question))
                .retrieve().body(HIT_LIST);
    }

    Answer ask(String question) {
        return client.post().uri("/api/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", question))
                .retrieve().body(Answer.class);
    }

    record Config(int topK, double similarityThreshold) {
    }

    /** 검색 결과 한 건. RagController.SourceRef와 같은 모양이다. */
    record Hit(int index, String source, Double score, String excerpt, String text) {
    }

    record Answer(String answer, List<Hit> sources) {
    }

    record Ingested(String fileName, String fileHash, int chunkCount) {
    }
}
