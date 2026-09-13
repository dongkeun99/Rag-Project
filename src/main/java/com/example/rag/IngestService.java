package com.example.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class IngestService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final VectorStore vectorStore;

    @Value("classpath:docs/sample.txt")
    private Resource sampleDoc;

    public IngestService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String text = sampleDoc.getContentAsString(StandardCharsets.UTF_8);
        Document document = new Document(text);

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(200)
                .withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(1000)
                .withKeepSeparator(true)
                .build();
        List<Document> chunks = splitter.split(List.of(document));

        log.info("=== 원본 1개 문서를 {}개 청크로 분할 ===", chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            log.info("[청크 {}] {}", i, chunks.get(i).getText());
        }

        vectorStore.add(chunks);
        log.info("=== 벡터 저장 완료 ===");
    }
}