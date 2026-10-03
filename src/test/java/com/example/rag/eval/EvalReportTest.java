package com.example.rag.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 평가 질문 세트를 측정해 보고서를 남긴다. 손으로 curl 36번 돌리고 표에 옮겨 적던 일을 대체한다.
 *
 * 떠 있는 앱과 Ollama가 필요하므로 @Tag("eval")로 묶어 ./gradlew test에서는 제외한다.
 * 실행: ./gradlew evalReport [-Prounds=3] [-Ponly=2,11] [-Plabel=인용강제]
 *
 * 자동 판정은 키워드 기반 1차 판정일 뿐이다. 답변 전문을 함께 남기므로 의미 판정은 사람이 한다.
 */
@Tag("eval")
class EvalReportTest {

    private static final Path QUESTIONS = Path.of(System.getProperty("eval.questions", "dev_docs/평가질문.yml"));
    private static final Path OUT_DIR = Path.of(System.getProperty("eval.out", "dev_docs/측정_원자료"));
    private static final String BASE_URL = System.getProperty("eval.baseUrl", "http://localhost:8080");
    private static final int ROUNDS = Integer.parseInt(System.getProperty("eval.rounds", "3"));
    private static final String ONLY = System.getProperty("eval.only", "");
    private static final String LABEL = System.getProperty("eval.label", "");

    /** 답변 하나에 수 분이 걸릴 수 있다. 첫 질문은 모델을 메모리에 올리느라 더 걸린다. */
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(10);

    @Test
    void writeReport() throws IOException {
        QuestionSet set = QuestionSet.load(QUESTIONS);
        List<QuestionSet.Question> questions = select(set.questions());
        RagApi api = new RagApi(BASE_URL, READ_TIMEOUT);

        RagApi.Config config = connect(api);
        List<RagApi.Ingested> ingested = api.ingested();
        int chunks = ingested.stream().mapToInt(RagApi.Ingested::chunkCount).sum();
        log("대상 %s · top-k %d · 임계값 %.2f · 파일 %d개 / %d청크 · %d문항 × %d회"
                .formatted(BASE_URL, config.topK(), config.similarityThreshold(),
                        ingested.size(), chunks, questions.size(), ROUNDS));

        // 1) 검색부터 본다. 검색이 틀렸는지 모델이 틀렸는지 가르려면 이 순서여야 한다(평가_질문.md).
        //    같은 질문·같은 코퍼스면 검색은 결정적이므로 1회만 잰다.
        log("");
        log("[검색] POST /api/search");
        Map<Integer, Search> searches = new LinkedHashMap<>();
        for (QuestionSet.Question question : questions) {
            List<RagApi.Hit> hits = api.search(question.question());
            Search search = Search.of(hits, question, set.watchedSource());
            searches.put(question.no(), search);
            log("  %2d번  조각 %d · 1위 %s · %s"
                    .formatted(question.no(), search.hits(), score(search.topScore()), search.ranks()));
        }

        // 2) 답변은 회차 단위로 전부 돌린다. 프롬프트 캐시 영향을 회차로 묶어 두려는 것이다.
        List<List<Answer>> rounds = new ArrayList<>();
        for (int round = 1; round <= ROUNDS; round++) {
            log("");
            log("[답변 %d/%d회차] POST /api/ask".formatted(round, ROUNDS));
            List<Answer> answers = new ArrayList<>();
            for (QuestionSet.Question question : questions) {
                long began = System.nanoTime();
                // 한 문항이 실패해도 측정을 계속한다. 36회를 수십 분 돌린 뒤 보고서를 통째로
                // 잃으면 안 된다. 실패는 답변 자리에 그대로 남겨 사람이 보게 한다.
                String answer;
                int hits;
                try {
                    RagApi.Answer result = api.ask(question.question());
                    answer = result.answer();
                    hits = result.sources() == null ? 0 : result.sources().size();
                } catch (RuntimeException e) {
                    answer = "요청 실패: " + e.getMessage();
                    hits = -1;
                }
                long seconds = Math.round((System.nanoTime() - began) / 1_000_000_000.0);
                QuestionSet.Verdict verdict = question.judge(answer, hits);
                answers.add(new Answer(round, answer, seconds, verdict));
                log("  %2d번  %3d초  %s%s".formatted(question.no(), seconds,
                        verdict.passed() ? "통과" : "확인 필요",
                        verdict.passed() ? "" : " — " + verdict.reason()));
            }
            rounds.add(answers);
        }

        // 3) 임계값 여유. 임계값 0으로 조회해야 무관한 질문의 최고 점수가 보인다.
        //    임계값은 코퍼스에 딸린 값이라 문서를 추가할 때마다 다시 재야 한다(4차 교훈).
        log("");
        log("[임계값 여유] POST /api/search?threshold=0");
        List<Margin> margins = new ArrayList<>();
        for (String question : set.unrelated()) {
            Margin margin;
            try {
                List<RagApi.Hit> hits = api.search(question, 0.0);
                RagApi.Hit top = hits.isEmpty() ? null : hits.get(0);
                // 0건이면 앱이 threshold 파라미터를 모르는 구버전이다. 재시작하면 점수가 보인다.
                margin = new Margin(question, top == null ? null : top.score(),
                        top == null ? "0건 — 앱을 재시작해 threshold 파라미터를 적용하세요" : top.source());
            } catch (RuntimeException e) {
                margin = new Margin(question, null, "조회 실패: " + e.getMessage());
            }
            margins.add(margin);
            log("  %s → %s".formatted(question, score(margin.topScore())));
        }

        Path report = write(set, questions, config, ingested.size(), chunks, searches, rounds, margins);
        log("");
        log("보고서: " + report.toAbsolutePath());
    }

    /** 앱이 떠 있지 않으면 여기서 끊고 무엇을 켜야 하는지 알려준다. */
    private RagApi.Config connect(RagApi api) {
        try {
            return api.config();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "앱에 연결하지 못했습니다(%s). Docker의 rag-postgres, Ollama, RagApplication이 떠 있는지 확인하세요."
                            .formatted(BASE_URL), e);
        }
    }

    /** -Ponly=2,11 로 일부 문항만 돌린다. 규칙 하나를 넣고 특정 문항만 빨리 볼 때 쓴다. */
    private List<QuestionSet.Question> select(List<QuestionSet.Question> all) {
        if (ONLY.isBlank()) {
            return all;
        }
        List<Integer> wanted = new ArrayList<>();
        for (String part : ONLY.split(",")) {
            wanted.add(Integer.parseInt(part.trim()));
        }
        return all.stream().filter(question -> wanted.contains(question.no())).toList();
    }

    private Path write(QuestionSet set,
                       List<QuestionSet.Question> questions,
                       RagApi.Config config,
                       int files,
                       int chunks,
                       Map<Integer, Search> searches,
                       List<List<Answer>> rounds,
                       List<Margin> margins) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        StringBuilder out = new StringBuilder();

        out.append("# 측정 원자료 %s%s\n\n".formatted(
                now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                LABEL.isBlank() ? "" : " — " + LABEL));
        out.append("""
                | 항목 | 값 |
                | --- | --- |
                | 대상 | %s |
                | 커밋 | %s |
                | top-k | %d |
                | 유사도 임계값 | %.2f |
                | 코퍼스 | 파일 %d개 / %d청크 |
                | 질문 세트 기준 | %s |
                | 문항 | %d개 |
                | 회차 | %d회 |

                자동 판정은 키워드 1차 판정이다. 의미 판정은 아래 **답변 전문**을 보고 확정하고,
                3회 중 몇 번 맞았는지로 적는다. 1회 성공을 해결로 적지 않는다.

                """.formatted(BASE_URL, commit(), config.topK(), config.similarityThreshold(),
                files, chunks, set.corpus(), questions.size(), ROUNDS));

        out.append("## 검색 (POST /api/search, 1회)\n\n");
        out.append("| # | 유형 | 조각 | 1위 점수 | 근거 문서 순위 | %s |\n".formatted(set.watchedSource()));
        out.append("| --- | --- | --- | --- | --- | --- |\n");
        for (QuestionSet.Question question : questions) {
            Search search = searches.get(question.no());
            out.append("| %d | %s | %d | %s | %s | %d |\n".formatted(
                    question.no(), question.type(), search.hits(),
                    score(search.topScore()), search.ranks(), search.watchedCount()));
        }

        out.append("\n## 답변 (POST /api/ask, %d회)\n\n".formatted(ROUNDS));
        out.append("| # | 유형 | 자동 판정 | 시간(회차순) | 중간값 | 확인할 것 |\n");
        out.append("| --- | --- | --- | --- | --- | --- |\n");
        for (int i = 0; i < questions.size(); i++) {
            QuestionSet.Question question = questions.get(i);
            List<Answer> perRound = roundsOf(rounds, i);
            long passed = perRound.stream().filter(answer -> answer.verdict().passed()).count();
            List<Long> times = perRound.stream().map(Answer::seconds).toList();
            out.append("| %d | %s | %d/%d %s | %s초 | %d초 | %s |\n".formatted(
                    question.no(), question.type(), passed, perRound.size(),
                    passed == perRound.size() ? "✅" : "⚠",
                    join(times), median(times), reasons(perRound)));
        }

        out.append("\n회차별 합계: ");
        List<String> totals = new ArrayList<>();
        for (int round = 0; round < rounds.size(); round++) {
            long sum = rounds.get(round).stream().mapToLong(Answer::seconds).sum();
            totals.add("%d회차 %d초".formatted(round + 1, sum));
        }
        out.append(String.join(" / ", totals));
        out.append("\n\n> 회차가 다른 개별 질문의 시간을 비교하면 안 된다. 프롬프트 캐시 상태에 좌우된다(5차 교훈).\n");

        out.append("\n## 임계값 여유 (무관한 질문, threshold=0)\n\n");
        out.append("| 무관한 질문 | 최고 점수 | 걸린 조각의 출처 |\n| --- | --- | --- |\n");
        for (Margin margin : margins) {
            out.append("| %s | %s | %s |\n".formatted(margin.question(), score(margin.topScore()), margin.source()));
        }
        out.append("\n").append(marginSummary(set, questions, searches, config, margins));

        out.append("\n## 답변 전문\n\n");
        out.append("회차 사이의 차이를 보려면 이 절을 이전 보고서와 diff 한다.\n");
        for (int i = 0; i < questions.size(); i++) {
            QuestionSet.Question question = questions.get(i);
            out.append("\n### %d번 (%s) — %s\n\n".formatted(
                    question.no(), question.type(), question.question()));
            out.append("- 기대: %s\n".formatted(question.expect()));
            out.append("- 키워드: %s\n".formatted(question.keywords().isEmpty()
                    ? "없음" : String.join(", ", question.keywords())));
            if (!question.forbidden().isEmpty()) {
                out.append("- 금지어: %s\n".formatted(String.join(", ", question.forbidden())));
            }
            for (Answer answer : roundsOf(rounds, i)) {
                out.append("\n#### %d회차 · %d초 · %s\n\n".formatted(
                        answer.round(), answer.seconds(),
                        answer.verdict().passed() ? "통과" : "확인 필요 — " + answer.verdict().reason()));
                out.append("```\n").append(answer.answer() == null ? "" : answer.answer().strip()).append("\n```\n");
            }
        }

        Files.createDirectories(OUT_DIR);
        String name = "측정_%s%s.md".formatted(
                now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")),
                LABEL.isBlank() ? "" : "_" + LABEL.replaceAll("[^0-9A-Za-z가-힣_-]", ""));
        Path path = OUT_DIR.resolve(name);
        Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
        return path;
    }

    /** 무관 최고와 관련 1위 최저 사이의 빈 구간을 계산한다. 임계값을 정하는 근거가 되는 숫자다. */
    private String marginSummary(QuestionSet set,
                                 List<QuestionSet.Question> questions,
                                 Map<Integer, Search> searches,
                                 RagApi.Config config,
                                 List<Margin> margins) {
        Double unrelatedTop = margins.stream()
                .map(Margin::topScore).filter(Objects::nonNull)
                .max(Double::compare).orElse(null);
        Double relatedLow = set.answerable().stream()
                .filter(questions::contains)
                .map(question -> searches.get(question.no()).topScore())
                .filter(Objects::nonNull)
                .min(Double::compare).orElse(null);
        if (unrelatedTop == null || relatedLow == null) {
            return "- 빈 구간을 계산할 수 없다(문항을 일부만 돌렸거나 조각이 없다).\n";
        }
        return """
                - 무관한 질문 최고: **%s**
                - 답이 있는 질문의 1위 최저: **%s**
                - 현재 임계값 %.2f → 여유 %+.4f
                - 빈 구간의 가운데: **%s** (애매하면 낮게 잡는다)
                """.formatted(score(unrelatedTop), score(relatedLow), config.similarityThreshold(),
                relatedLow - config.similarityThreshold(), score((unrelatedTop + relatedLow) / 2));
    }

    /** 측정값이 어느 코드에서 나온 것인지 남긴다. 프로세스를 띄우지 않고 .git을 읽는다. */
    private String commit() {
        try {
            String head = Files.readString(Path.of(".git/HEAD"), StandardCharsets.UTF_8).strip();
            if (!head.startsWith("ref: ")) {
                return head.substring(0, 7);
            }
            String ref = head.substring(5).strip();
            return Files.readString(Path.of(".git").resolve(ref), StandardCharsets.UTF_8).strip().substring(0, 7);
        } catch (IOException | RuntimeException e) {
            return "알 수 없음";
        }
    }

    private List<Answer> roundsOf(List<List<Answer>> rounds, int index) {
        return rounds.stream().map(round -> round.get(index)).toList();
    }

    /** 통과하지 못한 회차의 이유만 모은다. 통과면 빈 칸이다. */
    private String reasons(List<Answer> answers) {
        List<String> notes = new ArrayList<>();
        for (Answer answer : answers) {
            if (!answer.verdict().passed()) {
                notes.add("%d회차 %s".formatted(answer.round(), answer.verdict().reason()));
            }
        }
        return String.join("; ", notes);
    }

    private String join(List<Long> times) {
        return times.stream().map(String::valueOf).reduce((first, second) -> first + "/" + second).orElse("—");
    }

    /** 3회 측정의 중간값. 짝수면 뒤쪽을 쓴다. */
    private long median(List<Long> times) {
        List<Long> sorted = times.stream().sorted().toList();
        return sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
    }

    private String score(Double value) {
        return value == null ? "—" : String.format(Locale.ROOT, "%.4f", value);
    }

    private void log(String message) {
        System.out.println(message);
        System.out.flush();
    }

    /** 검색 한 번의 측정값. */
    private record Search(int hits, Double topScore, String ranks, int watchedCount) {

        static Search of(List<RagApi.Hit> hits, QuestionSet.Question question, String watched) {
            List<String> ranks = new ArrayList<>();
            for (String source : question.sources()) {
                int rank = rankOf(hits, hit -> source.equals(hit.source()));
                ranks.add(rank < 0 ? source + " 없음" : source + " " + rank + "위");
            }
            // 파일 단위 순위로는 안 보이는 것이 있다. 5번은 ID생성.md가 4위여도 정답인
            // "Table Id Generation" 조각은 7위다. top-k 8을 유지하는 근거가 그 조각이다.
            for (String keyword : question.chunkKeywords()) {
                int rank = rankOf(hits, hit -> hit.text() != null
                        && hit.text().toLowerCase().contains(keyword.toLowerCase()));
                ranks.add(rank < 0 ? "\"" + keyword + "\" 조각 없음" : "\"" + keyword + "\" 조각 " + rank + "위");
            }
            int watchedCount = (int) hits.stream()
                    .filter(hit -> watched.equals(hit.source()))
                    .count();
            return new Search(hits.size(),
                    hits.isEmpty() ? null : hits.get(0).score(),
                    ranks.isEmpty() ? "—" : String.join(", ", ranks),
                    watchedCount);
        }

        /** 조건에 맞는 첫 조각의 순위(1부터). 없으면 -1. */
        private static int rankOf(List<RagApi.Hit> hits, java.util.function.Predicate<RagApi.Hit> match) {
            for (int i = 0; i < hits.size(); i++) {
                if (match.test(hits.get(i))) {
                    return i + 1;
                }
            }
            return -1;
        }
    }

    /** 답변 한 번의 측정값. */
    private record Answer(int round, String answer, long seconds, QuestionSet.Verdict verdict) {
    }

    /** 무관한 질문 하나의 최고 점수. */
    private record Margin(String question, Double topScore, String source) {
    }
}
