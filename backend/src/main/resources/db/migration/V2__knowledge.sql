-- 원본 문서 (읽기만, 고치지 않는다). 쪽 하나 = 행 하나, 절 하나(긴 절은 나눠서) = 청크 하나
CREATE TABLE source_page (
    id           BIGSERIAL PRIMARY KEY,
    source       TEXT NOT NULL,                 -- guacamole-manual
    version      TEXT NOT NULL,                 -- 1.6.0
    path         TEXT NOT NULL,                 -- troubleshooting.html
    title        TEXT NOT NULL,
    url          TEXT NOT NULL,
    content_hash TEXT NOT NULL,                 -- 같으면 다시 임베딩하지 않는다
    fetched_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source, version, path)
);

CREATE TABLE chunk (
    id        BIGSERIAL PRIMARY KEY,
    page_id   BIGINT NOT NULL REFERENCES source_page (id) ON DELETE CASCADE,
    ordinal   INT NOT NULL,
    anchors   TEXT[] NOT NULL,                  -- 바깥 절부터 이 절까지의 id. 평가는 기대한 절이 조상이어도 맞다고 본다
    heading   TEXT NOT NULL,                    -- 절 제목 경로 "Troubleshooting > syslog > guacd errors"
    text      TEXT NOT NULL,
    tsv       TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', heading || ' ' || text)) STORED,
    embedding VECTOR(1024) NOT NULL
);

CREATE INDEX chunk_tsv_idx ON chunk USING gin (tsv);
CREATE INDEX chunk_embedding_idx ON chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX chunk_page_idx ON chunk (page_id, ordinal);
