package com.example.rag.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Cloudflare Workers AI의 채팅 스트리밍을 직접 읽는다.
 *
 * Spring AI의 스트리밍을 쓰지 못해 손으로 붙였다. 이유는 하나다 — <b>Cloudflare는 조각이
 * 숫자 하나일 때 {@code content}를 JSON 문자열이 아니라 숫자로 보낸다.</b> 직접 호출해
 * 받은 값이 {@code "content":1}, {@code "content":"\n"}, {@code "content":"2\n3"},
 * {@code "content":4}였다. 다른 글자가 섞이면({@code "j 2"}) 문자열로 온다.
 * Spring AI 2.0이 쓰는 공식 OpenAI Java SDK는 {@code content}를 문자열로만 받으므로
 * {@code `content` is invalid, received 2}로 죽고 답변이 중간에서 잘린다.
 * 여기서는 {@code JsonNode.asString()}으로 읽어 숫자든 문자열이든 그대로 받는다.
 *
 * 비스트리밍(/api/ask)은 전문을 한 번에 받아 이 문제가 없으므로 Spring AI가 그대로 맡는다.
 * 로컬 프로필도 해당 없다(Ollama는 Spring AI 자체 클라이언트를 쓴다). 그래서 @Profile("prod")다.
 *
 * 모델·온도·길이 상한은 spring.ai.openai.chat.options를 그대로 읽는다. 값을 여기에 따로
 * 두면 비스트리밍 경로와 어긋나서, 화면과 측정이 다른 설정으로 도는 사고가 난다.
 */
@Component
@Profile("prod")
public class CloudflareChatStreamer {

    private static final Logger log = LoggerFactory.getLogger(CloudflareChatStreamer.class);

    /** OpenAI 호환 스트림의 종료 표시. */
    private static final String DONE = "[DONE]";

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String model;
    private final double temperature;
    private final int maxTokens;

    public CloudflareChatStreamer(RestClient.Builder builder,
                                  ObjectMapper objectMapper,
                                  @Value("${rag.cloudflare.base-url}") String baseUrl,
                                  @Value("${rag.cloudflare.api-token}") String apiToken,
                                  @Value("${spring.ai.openai.chat.options.model}") String model,
                                  @Value("${spring.ai.openai.chat.options.temperature:0.2}") double temperature,
                                  @Value("${spring.ai.openai.chat.options.max-tokens:400}") int maxTokens) {
        this.client = builder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiToken)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        log.info("Cloudflare 스트리밍 사용: model={}, maxTokens={}", model, maxTokens);
    }

    /**
     * 답변 조각을 생성되는 대로 흘려보낸다.
     *
     * 소켓을 읽는 블로킹 코드라 boundedElastic에서 돌린다. 요청 스레드에서 읽으면
     * 그 스레드가 답변이 끝날 때까지 묶인다.
     */
    public Flux<String> stream(String system, String user) {
        return Flux.<String>create(sink -> read(system, user, sink), FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void read(String system, String user, FluxSink<String> sink) {
        try {
            client.post()
                    .uri("/chat/completions")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(requestBody(system, user))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new IllegalStateException(
                                    "Cloudflare 스트리밍이 " + response.getStatusCode() + "로 거절했습니다");
                        }
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                // 화면이 닫히면(EventSource close) 더 읽을 이유가 없다.
                                if (sink.isCancelled()) {
                                    break;
                                }
                                String text = textOf(line);
                                if (text != null && !text.isEmpty()) {
                                    sink.next(text);
                                }
                            }
                        }
                        return Boolean.TRUE;
                    });
            sink.complete();
        } catch (Exception e) {
            sink.error(e);
        }
    }

    /**
     * SSE 한 줄에서 답변 조각을 꺼낸다. 해당 줄에 조각이 없으면 null이다.
     *
     * {@code asString()}이 이 클래스의 존재 이유다 — 숫자 노드로 와도 "1"로 읽어 준다.
     */
    private String textOf(String line) {
        if (!line.startsWith("data:")) {
            return null;
        }
        String payload = line.substring("data:".length()).trim();
        if (payload.isEmpty() || DONE.equals(payload)) {
            return null;
        }
        try {
            JsonNode content = objectMapper.readTree(payload)
                    .path("choices").path(0).path("delta").path("content");
            return content.isMissingNode() || content.isNull() ? null : content.asString();
        } catch (Exception e) {
            // 한 줄이 깨졌다고 답변 전체를 버리지 않는다. 남은 조각은 계속 읽는다.
            log.warn("스트리밍 조각을 건너뜁니다: {}", e.toString());
            return null;
        }
    }

    private Map<String, Object> requestBody(String system, String user) {
        return Map.of(
                "model", model,
                "stream", true,
                "temperature", temperature,
                "max_tokens", maxTokens,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)));
    }
}
