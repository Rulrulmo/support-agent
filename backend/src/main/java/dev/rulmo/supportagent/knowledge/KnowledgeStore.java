package dev.rulmo.supportagent.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** source_page · chunk 저장과 검색 SQL. vector·배열·tsvector 때문에 JDBC로 */
@Repository
class KnowledgeStore {

	private final JdbcClient jdbc;

	KnowledgeStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	static String hash(ManualReader.Page page) {
		try {
			var md = MessageDigest.getInstance("SHA-256");
			md.update(page.title().getBytes(StandardCharsets.UTF_8));
			page.sections().forEach(s -> md.update((s.anchors() + "\u0000" + s.heading() + "\u0000" + s.text() + "\u0001").getBytes(StandardCharsets.UTF_8)));
			return HexFormat.of().formatHex(md.digest());
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	boolean unchanged(String source, String version, ManualReader.Page page, String hash) {
		return jdbc.sql("SELECT content_hash FROM source_page WHERE source = ? AND version = ? AND path = ?")
			.params(source, version, page.path()).query(String.class).optional().filter(hash::equals).isPresent();
	}

	/** 쪽을 통째로 바꾼다 (청크는 cascade로 지우고 다시 넣는다) */
	@Transactional
	void replace(String source, String version, ManualReader.Page page, String hash, List<float[]> embeddings) {
		jdbc.sql("DELETE FROM source_page WHERE source = ? AND version = ? AND path = ?").params(source, version, page.path()).update();
		long pageId = jdbc.sql("INSERT INTO source_page (source, version, path, title, url, content_hash) VALUES (?, ?, ?, ?, ?, ?) RETURNING id")
			.params(source, version, page.path(), page.title(), page.url(), hash).query(Long.class).single();
		for (int i = 0; i < page.sections().size(); i++) {
			var s = page.sections().get(i);
			jdbc.sql("INSERT INTO chunk (page_id, ordinal, anchors, heading, text, embedding) VALUES (?, ?, ?::text[], ?, ?, ?::vector)")
				.params(pageId, i, textArray(s.anchors()), s.heading(), s.text(), vector(embeddings.get(i))).update();
		}
	}

	/** 이번에 읽은 쪽에 없는 것은 지운다 (원본에서 사라진 쪽이 근거로 남지 않게) */
	int deleteMissing(String source, String version, List<String> paths) {
		return jdbc.sql("DELETE FROM source_page WHERE source = ? AND version = ? AND NOT (path = ANY(?::text[]))")
			.params(source, version, textArray(paths)).update();
	}

	/** 영어 전문 검색. 단어 중 하나라도 맞으면 후보 (websearch 문법은 어떤 입력에도 오류를 내지 않는다) */
	List<Long> keyword(List<String> terms, int limit) {
		if (terms.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("SELECT id FROM chunk, websearch_to_tsquery('english', ?) q WHERE tsv @@ q ORDER BY ts_rank_cd(tsv, q) DESC LIMIT ?")
			.params(String.join(" or ", terms), limit).query(Long.class).list();
	}

	/** 코사인 거리 순. ponytail: HNSW ef_search 기본 40이라 limit도 40 이하로 쓴다 (넘으면 결과가 40개에서 잘린다) */
	List<Long> vector(float[] query, int limit) {
		return jdbc.sql("SELECT id FROM chunk ORDER BY embedding <=> ?::vector LIMIT ?").params(vector(query), limit).query(Long.class).list();
	}

	/** ids 순서대로 */
	List<SearchHit> load(List<Long> ids, Map<Long, Double> scores) {
		if (ids.isEmpty()) {
			return List.of();
		}
		Map<Long, SearchHit> byId = jdbc.sql("""
				SELECT c.id, p.title, c.heading, p.url, c.anchors, c.text FROM chunk c JOIN source_page p ON p.id = c.page_id
				WHERE c.id = ANY(?::bigint[])""")
			.param("{" + ids.stream().map(String::valueOf).collect(Collectors.joining(",")) + "}")
			.query((rs, n) -> {
				List<String> anchors = strings(rs.getArray(5));
				return new SearchHit(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4) + "#" + anchors.getLast(), anchors,
						rs.getString(6), scores.getOrDefault(rs.getLong(1), 0.0));
			})
			.list().stream().collect(Collectors.toMap(SearchHit::chunkId, Function.identity()));
		return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
	}

	long count() {
		return jdbc.sql("SELECT count(*) FROM chunk").query(Long.class).single();
	}

	private static List<String> strings(Array a) throws SQLException {
		return Arrays.asList((String[]) a.getArray());
	}

	/** text[] 리터럴: 원소마다 따옴표 (id·경로에 쉼표가 있어도 깨지지 않게) */
	private static String textArray(List<String> xs) {
		return xs.stream().map(x -> "\"" + x.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").collect(Collectors.joining(",", "{", "}"));
	}

	private static String vector(float[] v) {
		var sb = new StringBuilder("[");
		for (int i = 0; i < v.length; i++) {
			sb.append(i == 0 ? "" : ",").append(v[i]);
		}
		return sb.append(']').toString();
	}
}
