-- 벡터 검색. 키워드 검색은 PostgreSQL 내장 전문 검색(tsvector, english)을 쓴다: 원본(Guacamole 매뉴얼)이 영어
CREATE EXTENSION IF NOT EXISTS vector;
