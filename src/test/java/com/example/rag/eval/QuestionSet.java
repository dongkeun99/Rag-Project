package com.example.rag.eval;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * dev_docs/평가질문.yml을 읽어 들인 평가 세트.
 * 사람이 읽는 설명은 dev_docs/평가_질문.md에 있고 이 파일은 같은 세트의 기계용 사본이다.
 * 질문을 코드에 고정하지 않는 이유: 7·8번이 성격이 바뀌고 11·12번이 교체된 것처럼 세트는 계속 바뀐다.
 */
record QuestionSet(String corpus, String watchedSource, List<Question> questions, List<String> unrelated) {

    static QuestionSet load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("평가 질문 파일을 찾을 수 없습니다: " + path.toAbsolutePath());
        }
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            root = new Yaml().load(reader);
        }
        if (root == null) {
            throw new IllegalStateException("평가 질문 파일이 비어 있습니다: " + path.toAbsolutePath());
        }

        List<Question> questions = new ArrayList<>();
        for (Map<String, Object> raw : maps(root.get("평가질문"))) {
            questions.add(new Question(
                    number(raw.get("번호")),
                    text(raw.get("유형")),
                    text(raw.get("질문")),
                    text(raw.get("기대")),
                    strings(raw.get("키워드")),
                    strings(raw.get("금지어")),
                    strings(raw.get("근거문서")),
                    strings(raw.get("근거조각키워드")),
                    raw.get("기대조각수") == null ? null : number(raw.get("기대조각수"))));
        }
        return new QuestionSet(
                text(root.get("코퍼스")),
                text(root.get("독점감시문서")),
                List.copyOf(questions),
                strings(root.get("무관질문")));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    private static List<String> strings(Object value) {
        if (value == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            result.add(String.valueOf(item));
        }
        return List.copyOf(result);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    /** 답이 있어야 하는 질문. 근거 문서를 비워 둔 9·11·12번이 "답 없음"이 기대값인 질문이다. */
    List<Question> answerable() {
        return questions.stream().filter(question -> !question.sources().isEmpty()).toList();
    }

    /**
     * 한 문항.
     *
     * @param keywords      답변에 모두 있어야 통과 후보(AND). 한 항목 안의 `|`는 택일이다
     * @param forbidden     하나라도 있으면 사람이 봐야 할 후보. 알려진 실패 모드를 잡는다
     * @param sources       기대 근거 문서. 검색에서 몇 위인지 기록해 검색/모델 책임을 가른다
     * @param chunkKeywords 문서가 아니라 조각 단위로 순위를 봐야 할 때. 5번의 "Table Id
     *                      Generation" 조각이 7위라는 것이 top-k 8을 유지하는 근거다
     * @param expectedHits  검색 조각 수가 이 값이어야 한다. 9번은 0건이어야 LLM을 타지 않는다
     */
    record Question(int no, String type, String question, String expect,
                    List<String> keywords, List<String> forbidden,
                    List<String> sources, List<String> chunkKeywords, Integer expectedHits) {

        /** 자동 1차 판정. 의미 판정은 사람이 보고서의 답변 전문을 보고 확정한다. */
        Verdict judge(String answer, int hits) {
            String lower = answer == null ? "" : answer.toLowerCase();
            List<String> missing = keywords.stream()
                    .filter(keyword -> !matches(lower, keyword))
                    .toList();
            List<String> found = forbidden.stream()
                    .filter(word -> lower.contains(word.toLowerCase()))
                    .toList();
            boolean hitsOk = expectedHits == null || expectedHits == hits;
            return new Verdict(missing, found, hitsOk);
        }

        /**
         * `|`로 묶은 것은 택일로 본다. 7번처럼 문서에 설치 경로가 둘(마법사·all-in-one 배포파일)
         * 있으면 어느 쪽으로 답해도 맞는데, 한 쪽만 적어 두면 맞는 답을 실패로 적게 된다.
         */
        private static boolean matches(String lowerAnswer, String keyword) {
            for (String option : keyword.split("\\|")) {
                if (lowerAnswer.contains(option.strip().toLowerCase())) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 자동 판정 결과. 키워드 미포함과 금지어 포함을 구분해 적는다.
     * 금지어는 맞는 답에도 걸릴 수 있어(2번이 Spring Security를 대조로 언급하는 경우) 실패로 단정하지 않는다.
     */
    record Verdict(List<String> missingKeywords, List<String> forbiddenFound, boolean hitsOk) {

        boolean passed() {
            return missingKeywords.isEmpty() && forbiddenFound.isEmpty() && hitsOk;
        }

        /** 보고서 비고 칸에 쓸 실패 이유. 통과면 빈 문자열이다. */
        String reason() {
            List<String> reasons = new ArrayList<>();
            if (!missingKeywords.isEmpty()) {
                reasons.add("키워드 없음 " + String.join("·", missingKeywords));
            }
            if (!forbiddenFound.isEmpty()) {
                reasons.add("금지어 " + String.join("·", forbiddenFound));
            }
            if (!hitsOk) {
                reasons.add("조각 수 불일치");
            }
            return String.join(", ", reasons);
        }
    }
}
