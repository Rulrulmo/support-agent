# support-agent

제품 문서를 근거로 답하는 **AI 상담원**. 원본 문서 검색(**RAG**)과 LLM이 정리하고 사람이 검수한 **LLM Wiki**를 함께 쓰고, 모든 개선을 평가셋으로 측정한다.
데모 대상: [Apache Guacamole](https://guacamole.apache.org/) 매뉴얼 1.6.0 (Apache-2.0, [NOTICE](NOTICE)).

- 계획·설계: [docs/PLAN.md](docs/PLAN.md)

## 구조
```
backend/         Java 21 · Spring Boot 4 · Spring Modulith · Spring AI (LLM은 LiteLLM 경유)
deploy/compose/  PostgreSQL 17 + pgvector, LiteLLM
```

## 실행
```bash
cd deploy/compose && cp .env.example .env    # LLM API 키 입력
docker compose up -d                         # postgres:5432, litellm:4000 (podman이면 podman compose)
cd backend && ./gradlew bootRun              # http://localhost:8080/actuator/health
```

## 지식·위키·평가 돌리기
```bash
curl -X POST localhost:8080/api/knowledge/ingest                     # 매뉴얼 수집 (45쪽, 임베딩 약 $0.03)
curl -X POST localhost:8080/api/wiki/compile                         # LLM Wiki 컴파일: 계획 → 쪽마다 작성 → 인용 검사 → draft (약 $5)
curl -X POST 'localhost:8080/api/wiki/compile?replan=false&missingOnly=true'   # 실패한 쪽만 다시
curl localhost:8080/api/wiki/index                                   # 위키 목차
curl -X POST localhost:8080/api/wiki/pages/{slug}/review -H 'Content-Type: application/json' -d '{"decision":"publish"}'
curl -X POST localhost:8080/api/wiki/lint                            # 원본이 바뀐 쪽 → stale
caffeinate -i curl -X POST 'localhost:8080/api/eval/answers?wiki=NONE' -o base.json       # 답변 평가 (60문항, 약 $2.5)
caffeinate -i curl -X POST 'localhost:8080/api/eval/answers?wiki=UNREVIEWED' -o wiki.json # 검수 전 위키까지 써서
```

## 테스트
```bash
cd backend && ./gradlew test    # Docker(또는 Podman) 필요: Testcontainers
```
