package com.example.rag.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cloudflare Workers AI의 bge-m3 임베딩을 직접 호출한다.
 *
 * Spring AI의 OpenAI 임베딩 클라이언트를 쓰지 못해 손으로 붙였다. Spring AI 2.0은 공식
 * OpenAI Java SDK를 쓰는데 그 SDK가 응답의 {@code usage}를 필수로 본다. Cloudflare의
 * OpenAI 호환 임베딩 응답에는 그 필드가 없어서(키가 object·data·model 셋뿐이다)
 * 호출이 {@code OpenAIInvalidDataException: `usage` is not set}으로 죽는다. 실제로 겪었다.
 * 채팅 쪽 응답에는 usage가 있으므로 채팅은 그대로 Spring AI가 처리한다.
 *
 * 모델을 로컬 Ollama와 같은 bge-m3로 두는 것이 핵심이다. 그래야 적재된 819청크와
 * similarity-threshold를 그대로 쓴다. 저장된 벡터와의 코사인이 최저 0.99988로 확인됐다.
 */
@Component
@Profile("prod")
public class CloudflareEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(CloudflareEmbeddingModel.class);

    /** bge-m3의 출력 차원. vector_store 컬럼(vector(1024))과 맞아야 한다. */
    private static final int DIMENSIONS = 1024;

    private final RestClient client;
    private final String model;

    public CloudflareEmbeddingModel(RestClient.Builder builder,
                                    @Value("${rag.cloudflare.base-url}") String baseUrl,
                                    @Value("${rag.cloudflare.api-token}") String apiToken,
                                    @Value("${rag.cloudflare.embedding-model:@cf/baai/bge-m3}") String model) {
        this.client = builder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiToken)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.model = model;
        log.info("Cloudflare 임베딩 사용: model={}, dimensions={}", model, DIMENSIONS);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> inputs = request.getInstructions();
        CloudflareResponse body = client.post()
                .uri("/embeddings")
                .body(new CloudflareRequest(model, inputs))
                .retrieve()
                .body(CloudflareResponse.class);

        if (body == null || body.data() == null || body.data().size() != inputs.size()) {
            throw new IllegalStateException(
                    "Cloudflare 임베딩 응답이 비었거나 개수가 다릅니다. 요청 " + inputs.size() + "건");
        }

        List<Embedding> embeddings = new ArrayList<>(inputs.size());
        for (int i = 0; i < body.data().size(); i++) {
            embeddings.add(new Embedding(toFloatArray(body.data().get(i).embedding()), i));
        }
        // Cloudflare는 usage를 주지 않으므로 모델 이름만 메타데이터에 남긴다.
        return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata(model, null, Map.of()));
    }

    /** 기본 구현은 "Test String"을 한 번 임베딩해 길이를 재므로, 호출을 아끼려고 못 박는다. */
    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private static float[] toFloatArray(List<Double> values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i).floatValue();
        }
        return result;
    }

    private record CloudflareRequest(String model, List<String> input) {
    }

    private record CloudflareResponse(List<Item> data) {
        private record Item(List<Double> embedding) {
        }
    }
}
