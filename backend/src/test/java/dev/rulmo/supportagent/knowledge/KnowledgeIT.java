package dev.rulmo.supportagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import dev.rulmo.supportagent.TestDatabase;

/** 실제 PostgreSQL(pgvector·전문 검색)에서 수집·검색. 임베딩은 가짜: 글에 "vpn"이 있으면 0번 축, 아니면 1번 축 */
@SpringBootTest
@Import(TestDatabase.class)
class KnowledgeIT {

	@MockitoBean
	EmbeddingModel embeddings;

	@Autowired
	KnowledgeIngest ingest;

	@Autowired
	KnowledgeSearch search;

	@Autowired
	JdbcClient jdbc;

	static float[] axis(String text) {
		float[] v = new float[1024];
		v[text.toLowerCase().contains("vpn") || text.contains("가상 사설망") ? 0 : 1] = 1;
		return v;
	}

	static ManualReader.Page page(String path, String... texts) {
		var sections = java.util.stream.IntStream.range(0, texts.length)
			.mapToObj(i -> new ManualReader.Section(List.of(path.replace(".html", ""), "s" + i), "Title > S" + i, texts[i])).toList();
		return new ManualReader.Page(path, "Title " + path, "https://docs.test/" + path, sections);
	}

	@BeforeEach
	void reset() {
		jdbc.sql("TRUNCATE source_page CASCADE").update();
		when(embeddings.embed(anyList())).thenAnswer(inv -> ((List<String>) inv.getArgument(0)).stream().map(KnowledgeIT::axis).toList());
		when(embeddings.embed(anyString())).thenAnswer(inv -> axis(inv.getArgument(0)));
	}

	@Test
	void koreanQuestionUsesEnglishTermsForKeywordsAndMeaningForVectors() {
		ingest.ingest(List.of(page("ldap.html", "Configure LDAP authentication with ldap-hostname and ldap-port properties."),
				page("network.html", "Connections over a VPN may buffer data.", "Unrelated text about display resolution.")));

		var byKeyword = search.search("LDAP 로그인이 안 돼요", 3, KnowledgeSearch.Mode.KEYWORD);
		assertThat(byKeyword).extracting(SearchHit::pageTitle).containsExactly("Title ldap.html");
		assertThat(byKeyword.getFirst().url()).isEqualTo("https://docs.test/ldap.html#s0");

		var byVector = search.search("가상 사설망 쓰면 화면이 늦게 떠요", 1, KnowledgeSearch.Mode.VECTOR);
		assertThat(byVector.getFirst().text()).contains("VPN");
		assertThat(search.search("LDAP 가상 사설망", 3)).hasSizeGreaterThanOrEqualTo(2);   // 하이브리드: 두 쪽 모두
	}

	@Test
	void reingestSkipsUnchangedAndDeletesMissingPages() {
		ingest.ingest(List.of(page("a.html", "Alpha page text long enough."), page("b.html", "Beta page text long enough.")));

		var r = ingest.ingest(List.of(page("a.html", "Alpha page text long enough.")));

		assertThat(r).containsEntry("unchangedPages", 1).containsEntry("deletedPages", 1).containsEntry("indexedPages", 0);
		assertThat(jdbc.sql("SELECT path FROM source_page").query(String.class).list()).containsExactly("a.html");
	}

	@Test
	void extractsAsciiTerms() {
		assertThat(KnowledgeSearch.terms("guacd 로그에 \"Unable to bind\" 나와요 (port 4822, guacamole.properties)"))
			.containsExactly("guacd", "unable", "to", "bind", "port", "4822", "guacamole.properties");
	}
}
