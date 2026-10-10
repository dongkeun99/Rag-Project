# 프로젝트 가이드라인 (CLAUDE.md)

## 1. 프로젝트 개요 및 기술 스택

- **목적**: 전자정부 표준프레임워크 문서에 답하는 개인 RAG 챗봇. 문서 적재 → 검색 → 답변(SSE 스트리밍) → 웹 화면까지 돌아간다. 회사 스택(Java·Spring)을 함께 익히는 학습 프로젝트이고, **남은 목표는 호스팅 배포 하나**다. 로컬 프로필은 전부 로컬에서 돌고, 배포 프로필(`prod`)은 관리형 DB와 외부 API를 쓴다.
- **백엔드**: Java 25 (Temurin), Spring Boot 4.1.1, Spring AI 2.0.1, Spring WebMVC, JdbcTemplate (JPA 없음)
- **프론트엔드**: `src/main/resources/static/index.html` 한 장 (HTML·CSS·JS, 빌드 없음)
- **데이터베이스**: PostgreSQL + pgvector. 로컬은 Docker 컨테이너 `rag-postgres`(PG 17), 배포는 Supabase(PG 17, 세션 풀러로 접속)
  - `vector_store`: 청크와 임베딩 (`vector(1024)`, HNSW 코사인). 로컬은 Spring AI가 자동 생성하고, 배포는 `initialize-schema: false`라 스키마를 미리 만들어 둔다
  - `ingested_file`: 파일별 해시·청크 수 기록. `IngestedFileRepository`가 기동 시 생성
- **LLM**
  - 로컬: Ollama. 임베딩 `bge-m3` (1024차원), 답변 `exaone3.5:2.4b` (연구/비상업 라이선스)
  - 배포(`prod`): Cloudflare Workers AI. 임베딩 `@cf/baai/bge-m3` (로컬과 같은 모델이라 적재된 벡터를 그대로 쓴다), 답변 `@cf/meta/llama-3.3-70b-instruct-fp8-fast` (잠정 — 측정으로 확정)
- **빌드 도구**: Gradle Wrapper (`gradlew`)
- **문서**
  - `README.md`: 프로젝트 개요, 기술 스택, 실행 방법만 담는다
  - `dev_docs/`: 단계별 개발 기록 (`1차_구현.md`, `2차_개선.md`)
  - `project_status.md`: 현재 상태와 할 일. 로컬 전용 문서

## 2. 개발 및 검증 명령어

코드를 수정한 뒤에는 아래 명령어로 직접 확인한다. PowerShell에서는 `./gradlew` 대신 `.\gradlew.bat`을 쓴다.

- **컴파일**: `./gradlew compileJava` — DB·Ollama 없이 돌아간다. 코드 수정 후 최소한 이것은 통과시킨다.
- **테스트**: `./gradlew test` — `contextLoads`가 DB에 연결하므로 `rag-postgres`가 떠 있어야 한다.
- **평가 측정**: `./gradlew evalReport` — 평가 질문 12개를 3회씩 재서 `dev_docs/측정_원자료/`에 보고서를 쓴다. 떠 있는 앱과 Ollama가 필요하고 30분 이상 걸린다. `-Prounds=1 -Ponly=2,11`로 일부만 빨리 볼 수 있다. `@Tag("eval")`이라 `./gradlew test`에서는 제외된다. 재는 방법과 판정 기준은 `dev_docs/평가_질문.md`의 "자동 측정"에 있다. 질문 세트는 `dev_docs/평가질문.yml`이고, 질문을 바꿀 때는 `평가_질문.md`와 함께 고친다.
- **로컬 서버 실행**: `./gradlew bootRun` (사용자는 IntelliJ에서 `RagApplication`을 실행한다)
  - 기동 로그에서 `Started RagApplication`과 `청킹 설정: chunkSize=250 토큰, minChunkSizeChars=120`을 확인한다.
  - 화면: `http://localhost:8080/`
- **배포 프로필로 실행**: `SPRING_PROFILES_ACTIVE=prod`만 주면 된다. 비밀값은 프로젝트 루트의 `.env`에서 읽는다 — `application-prod.yml`의 `spring.config.import: optional:file:./.env[.properties]`가 처리하므로 플러그인이나 의존성이 필요 없다. OS 환경변수가 `.env`보다 우선하므로 호스팅에서는 대시보드 값이 덮는다.
  - 기동 로그에서 `profile is active: "prod"`, `Cloudflare 임베딩 사용`, `문서 위치: classpath:docs` 세 줄을 확인한다. 활성 프로필 로그만 보고 적용됐다고 판단하면 안 된다(jar가 낡으면 조용히 기본값으로 돈다).
- **인프라 확인**
  - DB: `docker ps`로 `rag-postgres`가 Up인지 확인. 꺼져 있으면 프로젝트 루트에서 `docker compose up -d`
    - `docker-compose.yml`의 `name: rag-project`는 지우지 않는다. 이 값이 기존 데이터 볼륨(`rag-project_pgdata`)의 이름을 정하므로, 바뀌면 빈 DB가 새로 만들어진다.
  - Ollama: `ollama list`
- **API 확인**: 검색 품질은 LLM을 거치기 전에 `/api/search`로 먼저 본다.

  ```bash
  curl -X POST http://localhost:8080/api/search -H "Content-Type: application/json" -d '{"question": "실행환경의 다섯 가지 서비스 그룹이 뭔가요?"}'
  ```

  PowerShell의 `curl`은 `Invoke-WebRequest` 별칭이라 한글이 깨진다. `curl.exe`를 쓰고 JSON은 작은따옴표로 감싼다.
- **DB 확인**:

  ```bash
  docker exec rag-postgres psql -U raguser -d ragdb -tAc "SELECT metadata->>'source', count(*) FROM vector_store GROUP BY 1;"
  ```

### 설정을 바꿀 때 필요한 조치

| 바꾸는 값 | 필요한 조치 |
| --- | --- |
| `rag.chunk-size`, `rag.min-chunk-size-chars` | 재시작 후 `/api/ingest?force=true` (해시가 같아 일반 적재는 전부 SKIPPED) |
| `rag.similarity-threshold`, `rag.top-k` | 재시작만 (조회 시점에 적용) |
| 답변 모델, LLM 옵션, 시스템 프롬프트 | 재시작만 |
| 문서 폴더 파일 추가·수정 | `/api/ingest` 또는 화면의 "새 문서 읽기" |
| 임베딩 모델 | 벡터 차원이 바뀔 수 있어 전체 재적재 |

## 3. 코딩 컨벤션 및 아키텍처 스타일

- **패키지**: `api`는 컨트롤러와 요청·응답 타입, `ingest`는 문서 적재와 적재 기록, `ai`는 모델 제공처 어댑터. 새 코드는 역할에 맞는 패키지에 둔다.
- **의존성 주입**: 생성자 주입만 쓴다. 필드 `@Autowired`와 Lombok은 쓰지 않는다.
- **설정값**: 튜닝할 수 있는 값은 코드에 고정하지 말고 `application.yml`의 `rag:` 블록으로 빼서 `@Value("${rag.xxx:기본값}")`으로 생성자에서 받는다.
  - `rag:` 블록은 `spring:`과 같은 최상위 높이에 둔다. `spring.ai.ollama.chat.options` 안의 `top-k`는 LLM 샘플링 값이지 검색 개수가 아니다.
  - 파일 경로는 `D:/dev/rag-docs`처럼 `/`로 쓴다.
- **요청·응답 타입**: `record`로 만들고, 그 타입을 쓰는 클래스 안에 중첩해서 정의한다.
- **DB 접근**: `JdbcTemplate`과 텍스트 블록 SQL을 쓴다. 청크 저장·검색·삭제는 SQL이 아니라 `VectorStore` API로 한다.
- **주석과 로그**: 주석은 한국어로, "무엇을·왜"를 짧게 쓴다. 튜닝값에는 그 값을 고른 이유를 남긴다. 로그는 SLF4J, 한국어 메시지, `{}` 자리표시자를 쓴다.
- **들여쓰기**: 4칸 공백. Spring Initializr가 만든 `RagApplication.java`, `RagApplicationTests.java`는 탭이므로 그대로 둔다.
- **웹 화면**: `index.html` 한 파일을 유지하고 프레임워크나 빌드 도구를 붙이지 않는다. 색은 `:root`의 CSS 변수를 쓴다. 답변과 근거를 화면에 넣을 때는 반드시 HTML 이스케이프를 한다(XSS 방지).
- **RAG 튜닝 원칙**
  - 시스템 프롬프트는 "문서 조각으로 답하라"를 맨 앞에, "문서에서 찾을 수 없습니다"는 맨 뒤에 둔다. 작은 모델은 앞의 지시를 먼저 따르므로 규칙 수는 최소로 유지한다.
  - 임계값은 주제가 다른 질문을, 프롬프트는 주제는 맞지만 답이 없는 질문을 막는다. 둘 다 유지한다.
  - 튜닝값은 감이 아니라 측정한 숫자(점수, 순위, 응답 시간)로 정하고, 그 근거를 `dev_docs`에 기록한다.
  - 느려도 정답 조각을 앞에 두고 못 찾는 모델은 쓰지 않는다.

## 4. Git 커밋 규칙

Conventional Commits 형식에 한국어 메시지를 쓴다.

- `feat:` 새로운 기능 추가
- `fix:` 버그 수정
- `docs:` 문서 수정
- `refactor:` 동작을 바꾸지 않는 구조 개선
- `style:` 코드 의미에 영향을 주지 않는 스타일 변경 (포맷팅 등)
- `chore:` 빌드 설정, 의존성 등 기타 작업
- 예시: `feat: SSE 스트리밍 답변 엔드포인트 추가`

개인 프로젝트라 `main` 브랜치에 바로 커밋한다. 커밋과 푸시는 사용자가 요청할 때만 한다.

## 5. 문서 작성 규칙

- `README.md`는 프로젝트가 무엇인지, 왜 만들었는지, 기술 스택, 실행 방법만 담는다. 구현 과정이나 개선 내용을 README에 쌓지 않는다.
- 작업 기록은 `dev_docs/`에 단계별 파일로 추가한다. 파일명은 `N차_구현.md`, `N차_개선.md`, `N차_수정.md` 중 작업 성격에 맞게 정한다.
- 새 기록 문서를 추가하면 README의 "개발 기록" 표와 문서 상단의 이동 링크(`README · 1차 구현 · 2차 개선 …`)를 함께 갱신한다.

## 6. 절대 주의 사항 (제약 조건)

- 실제 비밀번호나 API 키(배포 시 외부 LLM 키 등)를 `application.yml`이나 코드에 직접 쓰지 않는다. DB 계정처럼 `${DB_PASSWORD:기본값}` 형태의 환경변수로 받고, `.env` 같은 파일은 Git에 올리지 않는다.
- `project_status.md`는 로컬 전용 문서이므로 커밋하지 않는다.
- `build.gradle`에 새 의존성을 추가해야 할 때는 먼저 사용자에게 동의를 구한다.
- 임베딩 모델이나 `dimensions: 1024`는 사용자 동의 없이 바꾸지 않는다. 벡터 차원이 바뀌면 적재된 데이터 전체를 다시 만들어야 한다.
- 적재된 데이터를 지우는 작업(`DELETE FROM vector_store`, `docker compose down -v` 등)은 먼저 사용자에게 확인받는다.
- **외부 서비스는 무료 플랜만 쓴다.** 어떤 사이트·서비스든 유료 플랜, 크레딧 충전, 유료 애드온은 쓰지 않는다.
  - 서비스를 고를 때 무료로 되는 길을 **가장 먼저** 찾는다. 유료안을 기본값으로 꺼내지 않는다.
  - 무료 티어의 **구체적 한도**(일일 호출 수, 토큰 수 등)를 공식 문서에서 확인하고, 그 한도가 이 프로젝트의 실사용량을 감당하는지 숫자로 따진 뒤 고른다. 무료 티어에서 빠지는 기능이 프로젝트에 필요한지도 함께 본다.
  - 무료로 되는지는 추정하지 말고 실제로 호출해 확인한다. 무료 플랜의 제약(요청 로깅·학습 이용 등)은 숨기지 않고 함께 알린다.
  - 유료가 불가피하다고 판단되면 진행하지 말고 멈춘다. 유료라는 사실과 금액을 명시해 사용자에게 먼저 묻는다.
  - 현재 구성은 전부 무료다 — DB는 Supabase 무료 티어, 답변과 임베딩은 모두 Cloudflare Workers AI(하루 10,000 뉴런을 둘이 같이 쓴다). OpenRouter도 검토했으나 무료 모델이 하루 50건이고 공용 풀 혼잡(429)이 잦아 쓰지 않는다.
