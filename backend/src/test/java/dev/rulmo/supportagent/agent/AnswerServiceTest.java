package dev.rulmo.supportagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;

import dev.rulmo.supportagent.knowledge.KnowledgeSearch;
import dev.rulmo.supportagent.knowledge.SearchHit;
import dev.rulmo.supportagent.knowledge.SourceSections;
import dev.rulmo.supportagent.wiki.WikiPage;
import dev.rulmo.supportagent.wiki.WikiSearch;

class AnswerServiceTest {

	static SearchHit hit(long id, String anchor) {
		return new SearchHit(id, "Troubleshooting", "Troubleshooting > " + anchor, "https://docs.test/troubleshooting.html#" + anchor,
				List.of("troubleshooting", anchor), "text " + anchor, 0);
	}

	static AnswerService service(KnowledgeSearch search, WikiSearch wiki, SourceSections sections, ChatModel chat) {
		return new AnswerService(search, wiki, sections, chat, "answer", WikiSearch.Use.PUBLISHED, "https://docs.test/", new ClassPathResource("prompts/answer.md"));
	}

	@Test
	void answersFromDocumentsAndTurnsCitationsIntoSources() {
		var search = mock(KnowledgeSearch.class);
		// 같은 절이 두 청크로 나뉘어 있으면 출처는 하나
		when(search.search(anyString(), anyInt())).thenReturn(List.of(hit(1, "no-graphics"), hit(2, "no-graphics"), hit(3, "unicode")));
		var prompts = new ArrayList<Prompt>();
		ChatModel chat = p -> {
			prompts.add(p);
			return new ChatResponse(List.of(new Generation(new AssistantMessage("프록시가 버퍼링하면 화면이 안 나옵니다 [1][2]. HTTPS를 쓰세요 [3]. 오류 코드 [519]는 그대로."))));
		};

		var a = service(search, mock(WikiSearch.class), mock(SourceSections.class), chat).answer("화면이 안 떠요", WikiSearch.Use.NONE);

		assertThat(a.text()).isEqualTo("프록시가 버퍼링하면 화면이 안 나옵니다. HTTPS를 쓰세요. 오류 코드 [519]는 그대로.");
		assertThat(a.cited()).extracting(AnswerService.Source::url).containsExactly("https://docs.test/troubleshooting.html#no-graphics",
				"https://docs.test/troubleshooting.html#unicode");
		var p = prompts.getFirst();
		assertThat(p.getOptions().getModel()).isEqualTo("answer");
		assertThat(p.getSystemMessage().getText()).startsWith("너는 Apache Guacamole").doesNotContain("<!--");
		assertThat(p.getUserMessage().getText()).contains("<document index=\"3\" type=\"manual\" section=\"Troubleshooting > unicode\">", "<question>\n화면이 안 떠요");
		assertThat(a.evidence()).containsExactly("text no-graphics", "text no-graphics", "text unicode");
	}

	@Test
	void wikiPagesComeFirstAndJudgeSeesOnlyTheirSourceSections() {
		var search = mock(KnowledgeSearch.class);
		when(search.search(anyString(), anyInt())).thenReturn(List.of(hit(9, "unicode")));
		var wiki = mock(WikiSearch.class);
		var page = new WikiPage("no-graphics", "troubleshooting", "화면이 안 나와요", "요약", "프록시가 버퍼링한다 [[troubleshooting.html#no-graphics]]",
				"published", List.of(), List.of("troubleshooting.html#no-graphics"));
		when(wiki.search(anyString(), anyInt(), org.mockito.ArgumentMatchers.eq(WikiSearch.Use.PUBLISHED))).thenReturn(List.of(page));
		var sections = mock(SourceSections.class);
		when(sections.text("troubleshooting.html#no-graphics")).thenReturn(java.util.Optional.of("ORIGINAL no-graphics section"));
		var prompts = new ArrayList<Prompt>();
		ChatModel chat = p -> {
			prompts.add(p);
			return new ChatResponse(List.of(new Generation(new AssistantMessage("프록시 때문입니다 [1]."))));
		};

		var a = service(search, wiki, sections, chat).answer("화면이 안 떠요");

		assertThat(prompts.getFirst().getUserMessage().getText()).contains("<document index=\"1\" type=\"wiki\" title=\"화면이 안 나와요\">\n프록시가 버퍼링한다\n",
				"<document index=\"2\" type=\"manual\"").doesNotContain("[[");
		assertThat(a.cited()).extracting(AnswerService.Source::kind).containsExactly("wiki", "manual");
		assertThat(a.cited().get(1).url()).isEqualTo("https://docs.test/troubleshooting.html#no-graphics");
		assertThat(a.evidence()).containsExactly("ORIGINAL no-graphics section", "text unicode");   // 위키 본문이 아니라 원본
	}
}
