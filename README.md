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

## 테스트
```bash
cd backend && ./gradlew test    # Docker(또는 Podman) 필요: Testcontainers
```
