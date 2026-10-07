package dev.rulmo.supportagent.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import dev.rulmo.supportagent.knowledge.KnowledgeSearch;
import dev.rulmo.supportagent.knowledge.SearchHit;

/**
 * 답변 한 턴 (M2): 원본 검색 → 지시문 + 자료 구획 → 답변 모델 → [n] 인용을 출처로.
 * ponytail: 대화 이력·툴(다시 검색·상담원 연결)·스트리밍은 채팅을 붙일 때(M4). 위키 먼저 보기는 M3
 */
@Service
public class AnswerService {

	static final int CONTEXT = 8;
	private static final Pattern CITE = Pattern.compile("[ \\t]*\\[(\\d{1,2}(?:\\s*,\\s*\\d{1,2})*)]");

	/**
	 * @param text    인용 표시를 뺀 답
	 * @param cited   답이 인용한 자료 (중복 절은 하나로)
	 * @param context 모델이 본 자료 전체 (평가 judge가 근거 대조에 쓴다)
	 */
	public record Answer(String text, List<SearchHit> cited, List<SearchHit> context, long latencyMs) {
	}

	private final KnowledgeSearch search;
	private final ChatModel chat;
	private final String model;
	private final String system;

	AnswerService(KnowledgeSearch search, ChatModel chat, @Value("${agent.answer-model:answer}") String model,
			@Value("classpath:prompts/answer.md") Resource system) {
		this.search = search;
		this.chat = chat;
		this.model = model;
		this.system = Prompts.read(system);
	}

	public Answer answer(String question) {
		long t0 = System.nanoTime();
		List<SearchHit> context = search.search(question, CONTEXT);
		var prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(documents(context) + "\n\n<question>\n" + question + "\n</question>")),
				chat.getOptions().mutate().model(model).build());
		String raw = chat.call(prompt).getResult().getOutput().getText();
		if (raw == null || raw.isBlank()) {
			throw new IllegalStateException("empty answer from " + model);
		}
		return new Answer(withoutCitations(raw, context.size()), cited(raw, context), context, (System.nanoTime() - t0) / 1_000_000);
	}

	/** 자료를 "데이터" 구획으로 감싼다: 지시와 섞이지 않게 (간접 프롬프트 주입 방어) */
	public static String documents(List<SearchHit> hits) {
		var sb = new StringBuilder("<documents>\n");
		for (int i = 0; i < hits.size(); i++) {
			var h = hits.get(i);
			sb.append("<document index=\"").append(i + 1).append("\" section=\"").append(h.heading()).append("\">\n")
				.append(h.text()).append("\n</document>\n");
		}
		return sb.append("</documents>").toString();
	}

	/** [n]이 가리키는 자료. 자료 번호 범위 밖 숫자는 인용이 아니다. 같은 절(주소)은 하나로 */
	static List<SearchHit> cited(String answer, List<SearchHit> hits) {
		var out = new LinkedHashMap<String, SearchHit>();
		Matcher m = CITE.matcher(answer);
		while (m.find()) {
			for (int i : indices(m.group(1))) {
				if (i >= 1 && i <= hits.size()) {
					out.putIfAbsent(hits.get(i - 1).url(), hits.get(i - 1));
				}
			}
		}
		return List.copyOf(out.values());
	}

	static String withoutCitations(String answer, int documents) {
		return CITE.matcher(answer).replaceAll(r -> indices(r.group(1)).stream().allMatch(i -> i >= 1 && i <= documents) ? ""
				: Matcher.quoteReplacement(r.group())).strip();
	}

	private static List<Integer> indices(String group) {
		var out = new ArrayList<Integer>();
		for (String s : group.split("\\s*,\\s*")) {
			out.add(Integer.parseInt(s.strip()));
		}
		return out;
	}

	/** 지시문 파일 읽기. <!-- 주석 -->은 사람용이라 모델에 보내지 않는다 */
	static final class Prompts {

		private Prompts() {
		}

		static String read(Resource r) {
			try {
				return r.getContentAsString(StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "").strip();
			}
			catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}
}
