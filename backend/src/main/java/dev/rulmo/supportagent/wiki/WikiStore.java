package dev.rulmo.supportagent.wiki;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
class WikiStore {

	private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
	};

	private static final String COLUMNS = "p.slug, p.kind, p.title, p.summary, p.body, p.status, p.problems, "
			+ "coalesce((SELECT array_agg(c.section_ref ORDER BY c.section_ref) FROM wiki_citation c WHERE c.page_id = p.id), '{}')";

	private final JdbcClient jdbc;
	private final JsonMapper json;

	WikiStore(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/** 새로 쓰거나 다시 쓴다. 다시 쓰면 검수를 다시 받는다 (draft) */
	@Transactional
	void save(String version, String slug, String kind, String title, String summary, String body, List<String> problems,
			Map<String, String> citations, float[] embedding) {
		long id = jdbc.sql("""
				INSERT INTO wiki_page (slug, kind, title, summary, body, status, problems, source_version, embedding)
				VALUES (?, ?, ?, ?, ?, 'draft', ?::jsonb, ?, ?::vector)
				ON CONFLICT (slug) DO UPDATE SET kind = excluded.kind, title = excluded.title, summary = excluded.summary, body = excluded.body,
				  status = 'draft', problems = excluded.problems, review_note = NULL, source_version = excluded.source_version,
				  embedding = excluded.embedding, updated_at = now()
				RETURNING id""")
			.params(slug, kind, title, summary, body, json.writeValueAsString(problems), version, vector(embedding)).query(Long.class).single();
		jdbc.sql("DELETE FROM wiki_citation WHERE page_id = ?").param(id).update();
		citations.forEach((ref, hash) -> jdbc.sql("INSERT INTO wiki_citation (page_id, section_ref, section_hash) VALUES (?, ?, ?)")
			.params(id, ref, hash).update());
	}

	List<WikiPage> list(List<String> statuses) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM wiki_page p WHERE p.status = ANY(?::text[]) ORDER BY p.kind, p.title")
			.param("{" + String.join(",", statuses) + "}").query((rs, n) -> page(rs)).list();
	}

	Optional<WikiPage> find(String slug) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM wiki_page p WHERE p.slug = ?").param(slug).query((rs, n) -> page(rs)).optional();
	}

	/** @return 바뀌었으면 true (없는 페이지면 false) */
	boolean setStatus(String slug, String status, String note) {
		return jdbc.sql("UPDATE wiki_page SET status = ?, review_note = ?, updated_at = now() WHERE slug = ?").params(status, note, slug).update() == 1;
	}

	void markStale(String slug, List<String> problems) {
		jdbc.sql("UPDATE wiki_page SET status = 'stale', problems = ?::jsonb, updated_at = now() WHERE slug = ?")
			.params(json.writeValueAsString(problems), slug).update();
	}

	/** 페이지 slug → (인용 절 → 쓸 때의 해시) */
	Map<String, Map<String, String>> citations() {
		return jdbc.sql("SELECT p.slug, c.section_ref, c.section_hash FROM wiki_citation c JOIN wiki_page p ON p.id = c.page_id WHERE p.status <> 'rejected'")
			.query((rs, n) -> new String[] { rs.getString(1), rs.getString(2), rs.getString(3) }).list().stream()
			.collect(Collectors.groupingBy(r -> r[0], Collectors.toMap(r -> r[1], r -> r[2])));
	}

	List<WikiPage> search(float[] query, List<String> statuses, int k) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM wiki_page p WHERE p.status = ANY(?::text[]) ORDER BY p.embedding <=> ?::vector LIMIT ?")
			.params("{" + String.join(",", statuses) + "}", vector(query), k).query((rs, n) -> page(rs)).list();
	}

	/** 마지막 계획의 페이지 목록 (wiki_log의 plan) */
	Optional<tools.jackson.databind.JsonNode> lastPlan() {
		return jdbc.sql("SELECT detail FROM wiki_log WHERE op = 'plan' ORDER BY id DESC LIMIT 1").query(String.class).optional()
			.map(json::readTree).map(d -> d.path("pages")).filter(p -> p.isArray() && !p.isEmpty());
	}

	void log(String op, String slug, Map<String, ?> detail) {
		jdbc.sql("INSERT INTO wiki_log (op, page_slug, detail) VALUES (?, ?, ?::jsonb)").params(op, slug, json.writeValueAsString(detail)).update();
	}

	private WikiPage page(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new WikiPage(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
				json.readValue(rs.getString(7), STRINGS), List.of((String[]) rs.getArray(8).getArray()));
	}

	private static String vector(float[] v) {
		var sb = new StringBuilder("[");
		for (int i = 0; i < v.length; i++) {
			sb.append(i == 0 ? "" : ",").append(v[i]);
		}
		return sb.append(']').toString();
	}
}
