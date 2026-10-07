package dev.rulmo.supportagent.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 원본을 절 단위로 본다 (위키가 쓴다). 절 참조 = "쪽.html#절id" — 다시 수집해도 바뀌지 않는다 (청크 id는 바뀐다).
 * 절의 글 = 그 절이 자기 몫으로 가진 청크들을 순서대로 이은 것 (하위 절은 따로).
 */
@Service
public class SourceSections {

	/** @param chars 절 글의 길이 (페이지 계획 때 분량 판단) */
	public record Section(String ref, String page, String pageTitle, String heading, int chars) {
	}

	private final JdbcClient jdbc;

	SourceSections(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 모든 절, 쪽·순서대로 */
	public List<Section> outline() {
		return jdbc.sql("""
				SELECT p.path || '#' || c.anchors[array_length(c.anchors, 1)] AS ref, p.path, p.title, min(c.heading), sum(length(c.text))::int
				FROM chunk c JOIN source_page p ON p.id = c.page_id
				GROUP BY p.id, p.path, p.title, c.anchors[array_length(c.anchors, 1)]
				ORDER BY p.id, min(c.ordinal)""")
			.query((rs, n) -> new Section(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)))
			.list();
	}

	/** 절의 글. 없는 절이면 empty (원본에서 사라졌거나 잘못된 참조) */
	public Optional<String> text(String ref) {
		int hash = ref.indexOf('#');
		if (hash < 0) {
			return Optional.empty();
		}
		List<String> parts = jdbc.sql("""
				SELECT c.text FROM chunk c JOIN source_page p ON p.id = c.page_id
				WHERE p.path = ? AND c.anchors[array_length(c.anchors, 1)] = ? ORDER BY c.ordinal""")
			.params(ref.substring(0, hash), ref.substring(hash + 1)).query(String.class).list();
		return parts.isEmpty() ? Optional.empty() : Optional.of(String.join("\n\n", parts));
	}

	public static String hash(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
