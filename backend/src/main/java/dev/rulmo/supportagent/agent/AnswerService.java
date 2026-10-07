package dev.rulmo.supportagent.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import dev.rulmo.supportagent.knowledge.SourceSections;
import dev.rulmo.supportagent.llm.LlmOptions;
import dev.rulmo.supportagent.wiki.WikiPage;
import dev.rulmo.supportagent.wiki.WikiSearch;

/**
 * 답변 한 턴: (위키 페이지 →) 원본 절 → 지시문 + 자료 구획 → 답변 모델 → [n] 인용을 출처로.
 * 위키를 쓰면 상위 위키 3쪽 + 원본 5절, 안 쓰면 원본 8절 (자료 양을 비슷하게 맞춰 비교한다).
 * ponytail: 대화 이력·툴(다시 검색·상담원 연결)·스트리밍은 채팅을 붙일 때(M4)
 */
@Service
public class AnswerService {

	static final int RAW_ONLY = 8;
	static final int WIKI_PAGES = 3;
	static final int RAW_WITH_WIKI = 5;
	private static final Pattern CITE = Pattern.compile("[ \\t]*\\[(\\d{1,2}(?:\\s*,\\s*\\d{1,2})*)]");

	/** @param kind manual(원본 절) | wiki(위키 페이지) */
	public record Source(String kind, String title, String url) {
	}

	/**
	 * @param text     인용 표시를 뺀 답
	 * @param cited    답이 인용한 출처. 위키 페이지를 인용했으면 그 페이지와, 그 페이지가 인용한 원본 절
	 * @param evidence 근거 대조용 원본 글 (평가 judge가 본다): 원본 절 + 모델이 본 위키 페이지들이 인용한 원본 절. 위키 본문은 넣지 않는다
	 */
	public record Answer(String text, List<Source> cited, List<String> evidence, long latencyMs) {
	}

	private final KnowledgeSearch search;
	private final WikiSearch wiki;
	private final SourceSections sections;
	private final ChatModel chat;
	private final String model;
	private final WikiSearch.Use defaultWikiUse;
	private final String sourceBaseUrl;
	private final String system;

	AnswerService(KnowledgeSearch search, WikiSearch wiki, SourceSections sections, ChatModel chat,
			@Value("${agent.answer-model:answer}") String model, @Value("${agent.wiki:PUBLISHED}") WikiSearch.Use defaultWikiUse,
			@Value("${knowledge.source.base-url}") String sourceBaseUrl, @Value("classpath:prompts/answer.md") Resource system) {
		this.search = search;
		this.wiki = wiki;
		this.sections = sections;
		this.chat = chat;
		this.model = model;
		this.defaultWikiUse = defaultWikiUse;
		this.sourceBaseUrl = sourceBaseUrl.endsWith("/") ? sourceBaseUrl : sourceBaseUrl + "/";
		this.system = read(system);
	}

	public Answer answer(String question) {
		return answer(question, defaultWikiUse);
	}

	/** @param wikiUse 위키를 쓸지, 어떤 상태까지 쓸지 (평가에서 비교) */
	public Answer answer(String question, WikiSearch.Use wikiUse) {
		long t0 = System.nanoTime();
		List<WikiPage> pages = wiki.search(question, WIKI_PAGES, wikiUse);
		List<SearchHit> hits = search.search(question, pages.isEmpty() ? RAW_ONLY : RAW_WITH_WIKI);
		var prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(documents(pages, hits) + "\n\n<question>\n" + question + "\n</question>")),
				LlmOptions.of(chat, model, null, Duration.ofMinutes(2)));
		String raw = chat.call(prompt).getResult().getOutput().getText();
		if (raw == null || raw.isBlank()) {
			throw new IllegalStateException("empty answer from " + model);
		}
		return new Answer(withoutCitations(raw, pages.size() + hits.size()), cited(raw, pages, hits), evidence(pages, hits),
				(System.nanoTime() - t0) / 1_000_000);
	}

	/** 자료를 "데이터" 구획으로 감싼다: 지시와 섞이지 않게 (간접 프롬프트 주입 방어). 위키가 먼저, 번호는 이어서 */
	static String documents(List<WikiPage> pages, List<SearchHit> hits) {
		var sb = new StringBuilder("<documents>\n");
		int n = 1;
		for (var p : pages) {
			sb.append("<document index=\"").append(n++).append("\" type=\"wiki\" title=\"").append(p.title()).append("\">\n")
				.append(p.plainBody()).append("\n</document>\n");
		}
		for (var h : hits) {
			sb.append("<document index=\"").append(n++).append("\" type=\"manual\" section=\"").append(h.heading()).append("\">\n")
				.append(h.text()).append("\n</document>\n");
		}
		return sb.append("</documents>").toString();
	}

	/** [n]이 가리키는 출처. 번호 범위 밖 숫자는 인용이 아니다. 같은 주소는 하나로 */
	List<Source> cited(String answer, List<WikiPage> pages, List<SearchHit> hits) {
		var out = new LinkedHashMap<String, Source>();
		Matcher m = CITE.matcher(answer);
		while (m.find()) {
			for (int i : indices(m.group(1))) {
				if (i >= 1 && i <= pages.size()) {
					var p = pages.get(i - 1);
					out.putIfAbsent("wiki:" + p.slug(), new Source("wiki", p.title(), null));
					p.citedRefs().forEach(ref -> out.putIfAbsent(sourceBaseUrl + ref, new Source("manual", ref, sourceBaseUrl + ref)));
				}
				else if (i > pages.size() && i <= pages.size() + hits.size()) {
					var h = hits.get(i - pages.size() - 1);
					out.putIfAbsent(h.url(), new Source("manual", h.heading(), h.url()));
				}
			}
		}
		return List.copyOf(out.values());
	}

	/** 원본 글만: 위키 페이지는 그 페이지가 인용한 원본 절로 바꿔 넣는다 (위키의 잘못도 근거 없는 주장으로 잡히게) */
	private List<String> evidence(List<WikiPage> pages, List<SearchHit> hits) {
		var refs = new LinkedHashSet<String>();
		pages.forEach(p -> refs.addAll(p.citedRefs()));
		var out = new ArrayList<String>();
		refs.forEach(ref -> sections.text(ref).ifPresent(out::add));
		hits.forEach(h -> out.add(h.text()));
		return out;
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

	/** 지시문 파일. <!-- 주석 -->은 사람용이라 모델에 보내지 않는다 */
	static String read(Resource r) {
		try {
			return r.getContentAsString(StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "").strip();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
