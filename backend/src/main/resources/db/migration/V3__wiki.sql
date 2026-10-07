-- LLM Wiki (docs/PLAN.md §4): LLM이 원본을 정리해 쓰고 사람이 검수하는 한국어 페이지. 답변에는 published만 쓴다
CREATE TABLE wiki_page (
    id             BIGSERIAL PRIMARY KEY,
    slug           TEXT NOT NULL UNIQUE,
    kind           TEXT NOT NULL,                  -- troubleshooting | topic | reference
    title          TEXT NOT NULL,
    summary        TEXT NOT NULL,                  -- 한 줄. 목차(index)에 보인다
    body           TEXT NOT NULL,                  -- 마크다운. 문장·항목마다 원본 절 인용 [[page.html#anchor]]
    status         TEXT NOT NULL,                  -- draft | published | rejected | stale
    problems       JSONB NOT NULL DEFAULT '[]',    -- 인용 검사·린트가 찾은 문제 (검수자가 본다)
    review_note    TEXT,
    source_version TEXT NOT NULL,
    embedding      VECTOR(1024) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX wiki_page_embedding_idx ON wiki_page USING hnsw (embedding vector_cosine_ops);

-- 페이지가 인용한 원본 절과 쓸 때의 내용 해시: 원본이 바뀌면 린트가 stale로 표시한다
CREATE TABLE wiki_citation (
    page_id      BIGINT NOT NULL REFERENCES wiki_page (id) ON DELETE CASCADE,
    section_ref  TEXT NOT NULL,                    -- troubleshooting.html#no-graphics-appear
    section_hash TEXT NOT NULL,
    PRIMARY KEY (page_id, section_ref)
);

-- 위키가 어떻게 바뀌어 왔는지 (컴파일·검수·린트). 쌓기만 한다
CREATE TABLE wiki_log (
    id        BIGSERIAL PRIMARY KEY,
    at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    op        TEXT NOT NULL,
    page_slug TEXT,
    detail    JSONB NOT NULL DEFAULT '{}'
);
