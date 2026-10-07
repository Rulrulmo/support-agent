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

class AnswerServiceTest {

	static SearchHit hit(long id, String anchor) {
		return new SearchHit(id, "Troubleshooting", "Troubleshooting > " + anchor, "https://docs.test/troubleshooting.html#" + anchor,
				List.of("troubleshooting", anchor), "text " + anchor, 0);
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

		var a = new AnswerService(search, chat, "answer", new ClassPathResource("prompts/answer.md")).answer("화면이 안 떠요");

		assertThat(a.text()).isEqualTo("프록시가 버퍼링하면 화면이 안 나옵니다. HTTPS를 쓰세요. 오류 코드 [519]는 그대로.");
		assertThat(a.cited()).extracting(SearchHit::url).containsExactly("https://docs.test/troubleshooting.html#no-graphics",
				"https://docs.test/troubleshooting.html#unicode");
		var p = prompts.getFirst();
		assertThat(p.getOptions().getModel()).isEqualTo("answer");
		assertThat(p.getSystemMessage().getText()).startsWith("너는 Apache Guacamole").doesNotContain("<!--");
		assertThat(p.getUserMessage().getText()).contains("<document index=\"3\" section=\"Troubleshooting > unicode\">", "<question>\n화면이 안 떠요");
	}
}
