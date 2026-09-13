package com.example.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
public class RagController {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public RagController(VectorStore vectorStore, ChatClient.Builder builder) {
        this.vectorStore = vectorStore;
        this.chatClient = builder.build();
    }

    // 검색만 확인 - 어떤 청크가 뽑히는지 보기
    @GetMapping("/search")
    public List<String> search(@RequestParam String q) {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(q).topK(3).build()
        );
        return docs.stream().map(Document::getText).toList();
    }

    // 검색 + 답변 생성
    @GetMapping("/ask")
    public String ask(@RequestParam String q) {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(q).topK(3).build()
        );

        String context = docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));

        String prompt = """
                아래 참고 문서만을 근거로 질문에 답하세요.
                문서에 없는 내용은 "문서에서 찾을 수 없습니다"라고 답하세요.

                [참고 문서]
                %s

                [질문]
                %s
                """.formatted(context, q);

        return chatClient.prompt().user(prompt).call().content();
    }
}