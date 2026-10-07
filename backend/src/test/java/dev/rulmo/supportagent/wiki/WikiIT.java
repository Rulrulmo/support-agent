package dev.rulmo.supportagent.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import dev.rulmo.supportagent.TestDatabase;

/** 실제 DB에서 컴파일(가짜 LLM) → 인용·해시 저장 → 검수 → 원본이 바뀌면 stale */
@SpringBootTest
@Import({ TestDatabase.class, WikiIT.FakeLlm.class })
class WikiIT {

	/** 계획 한 번, 페이지 한 번: 차례로 돌려준다 */
	static class FakeLlm {

		static final List<String> SCRIPT = new ArrayList<>();
		static final List<Prompt> PROMPTS = new ArrayList<>();

		@Bean
		@Primary
		ChatModel fakeChat() {
			return new ChatModel() {
				@Override
				public ChatResponse call(Prompt p) {
					PROMPTS.add(p);
					return new ChatResponse(List.of(new Generation(new AssistantMessage(SCRIPT.removeFirst()))));
				}

				@Override
				public reactor.core.publisher.Flux<ChatResponse> stream(Prompt p) {
					return reactor.core.publisher.Flux.just(call(p));
				}

				@Override
				public ChatOptions getOptions() {
					return ChatOptions.builder().build();
				}
			};
		}
	}

	@MockitoBean
	EmbeddingModel embeddings;

	@Autowired
	WikiCompiler compiler;

	@Autowired
	WikiLint lint;

	@Autowired
	WikiStore store;

	@Autowired
	WikiSearch search;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void seed() {
		jdbc.sql("TRUNCATE source_page, wiki_page, wiki_log CASCADE").update();
		float[] v = new float[1024];
		v[0] = 1;
		when(embeddings.embed(anyString())).thenReturn(v);
		when(embeddings.embed(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).stream().map(x -> v).toList());
		long page = jdbc.sql("INSERT INTO source_page (source, version, path, title, url, content_hash) VALUES ('guacamole-manual', '1.6.0', "
				+ "'troubleshooting.html', 'Troubleshooting', 'https://docs.test/troubleshooting.html', 'h') RETURNING id").query(Long.class).single();
		for (var c : List.of(List.of("no-graphics-appear", "Proxies may buffer data. Use HTTPS."), List.of("guacd-errors", "Check syslog for guacd errors."),
				List.of("libguac", "Developer internals."))) {
			jdbc.sql("INSERT INTO chunk (page_id, ordinal, anchors, heading, text, embedding) VALUES (?, 0, ?::text[], 'T > x', ?, ?::vector)")
				.params(page, "{troubleshooting," + c.get(0) + "}", c.get(1), "[1" + ",0".repeat(1023) + "]").update();
		}
		FakeLlm.SCRIPT.clear();
		FakeLlm.PROMPTS.clear();
	}

	@Test
	void compilesDraftWithCheckedCitationsThenReviewAndLint() throws Exception {
		FakeLlm.SCRIPT.add("""
				{"pages":[{"slug":"no-graphics","kind":"troubleshooting","title":"화면이 안 나와요",
				  "sections":["troubleshooting.html#no-graphics-appear","troubleshooting.html#not-in-outline"]}]}""");
		FakeLlm.SCRIPT.add("""
				SUMMARY: waiting for first update 뒤 끊김
				## 원인
				프록시가 버퍼링한다 "따옴표"도 그대로 [[troubleshooting.html#no-graphics-appear]]

				## 해결
				1. HTTPS를 쓴다""");

		var r = compiler.compile(true, false);

		assertThat(r).containsEntry("planned", 1).containsEntry("written", 1);
		var outline = FakeLlm.PROMPTS.getFirst().getUserMessage().getText();
		assertThat(outline).contains("troubleshooting.html#no-graphics-appear | T > x | 35");
		var src = FakeLlm.PROMPTS.get(1).getUserMessage().getText();
		assertThat(src).contains("<source ref=\"troubleshooting.html#no-graphics-appear\">\nProxies may buffer data.").doesNotContain("not-in-outline");
		var page = store.find("no-graphics").orElseThrow();
		assertThat(page.status()).isEqualTo("draft");
		assertThat(page.citedRefs()).containsExactly("troubleshooting.html#no-graphics-appear");
		assertThat(page.problems()).containsExactly("인용 없는 줄: 1. HTTPS를 쓴다");

		// 검수 전에는 운영 검색에 안 나오고, 실험(UNREVIEWED)에는 나온다
		assertThat(search.search("화면", 3, WikiSearch.Use.PUBLISHED)).isEmpty();
		assertThat(search.search("화면", 3, WikiSearch.Use.UNREVIEWED)).hasSize(1);
		store.setStatus("no-graphics", "published", "확인함");
		assertThat(search.search("화면", 3, WikiSearch.Use.PUBLISHED)).extracting(WikiPage::slug).containsExactly("no-graphics");

		// 원본이 바뀌면 stale
		assertThat(lint.lint().get("stale")).isEqualTo(Map.of());
		jdbc.sql("UPDATE chunk SET text = 'Proxies may buffer data. Use HTTPS or WebSocket.' WHERE anchors[2] = 'no-graphics-appear'").update();
		assertThat(((Map<?, ?>) lint.lint().get("stale")).keySet()).hasToString("[no-graphics]");
		assertThat(store.find("no-graphics").orElseThrow().status()).isEqualTo("stale");
		assertThat(jdbc.sql("SELECT op FROM wiki_log ORDER BY id").query(String.class).list()).containsExactly("plan", "write", "lint", "lint");

		// 계획은 다시 하지 않고 페이지만 다시 쓴다 (LLM 호출: 페이지 하나)
		assertThat(compiler.compile(false, true)).containsEntry("planned", 0);   // 빠진 페이지만: 없다
		FakeLlm.SCRIPT.add("```markdown\nSUMMARY: 다시\n다시 쓴 본문 [[troubleshooting.html#no-graphics-appear]]\n```");
		assertThat(compiler.compile(false, false)).containsEntry("written", 1);
		assertThat(store.find("no-graphics").orElseThrow().status()).isEqualTo("draft");   // 다시 쓰면 다시 검수
	}
}
