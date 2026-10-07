package dev.rulmo.supportagent.wiki;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import dev.rulmo.supportagent.knowledge.SourceSections;
import dev.rulmo.supportagent.llm.LlmOptions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * LLM Wiki 컴파일 (docs/PLAN.md §4): 원본 절 목록 → 페이지 계획 → 페이지 작성 → 인용 검사 → draft 저장.
 * 스키마(어떤 페이지를 어떤 형식으로 쓰나)는 prompts/wiki-plan.md · wiki-page.md. 검수는 사람이 한다 (WikiController).
 */
@Service
class WikiCompiler {

	private static final Logger log = LoggerFactory.getLogger(WikiCompiler.class);
	static final Pattern CITATION = Pattern.compile("[ \\t]*\\[\\[([a-z0-9-]+\\.html#[^\\]\\s]+)]]");
	/** 상담 대상이 아닌 개발자 장: 계획에 넣지 않는다 */
	private static final Set<String> DEVELOPER_PAGES = Set.of("guacamole-protocol.html", "libguac.html", "guacamole-common.html",
			"guacamole-common-js.html", "guacamole-ext.html", "custom-protocols.html", "custom-auth.html", "event-listeners.html",
			"writing-you-own-guacamole-app.html", "protocol-reference.html", "jdbc-auth-schema.html");
	private static final int MAX_SOURCE_CHARS = 25_000;
	private static final int CONCURRENCY = 4;

	record Plan(String slug, String kind, String title, List<String> sections) {
	}

	private final SourceSections sections;
	private final WikiStore store;
	private final ChatModel chat;
	private final EmbeddingModel embeddings;
	private final JsonMapper json;
	private final String model;
	private final String version;
	private final String planPrompt;
	private final String pagePrompt;

	WikiCompiler(SourceSections sections, WikiStore store, ChatModel chat, EmbeddingModel embeddings, JsonMapper json,
			@Value("${wiki.model:wiki}") String model, @Value("${knowledge.source.version}") String version,
			@Value("classpath:prompts/wiki-plan.md") Resource planPrompt, @Value("classpath:prompts/wiki-page.md") Resource pagePrompt)
			throws java.io.IOException {
		this.sections = sections;
		this.store = store;
		this.chat = chat;
		this.embeddings = embeddings;
		this.json = json;
		this.model = model;
		this.version = version;
		this.planPrompt = read(planPrompt);
		this.pagePrompt = read(pagePrompt);
	}

	/**
	 * 계획하고 모든 페이지를 쓴다. 페이지 하나가 실패해도 나머지는 계속.
	 *
	 * @param replan      false면 마지막 계획(wiki_log)을 다시 쓴다: 계획은 큰 호출이라 페이지 작성만 다시 할 때 아낀다
	 * @param missingOnly 아직 없는 페이지만 쓴다 (실패한 것만 다시)
	 */
	Map<String, Object> compile(boolean replan, boolean missingOnly) throws InterruptedException {
		var outline = sections.outline().stream().filter(s -> !DEVELOPER_PAGES.contains(s.page())).toList();
		List<Plan> plans = replan ? List.of() : store.lastPlan().map(this::plans).orElse(List.of());
		if (plans.isEmpty()) {
			plans = plan(outline);
			store.log("plan", null, Map.of("pages", plans, "sections", outline.size()));
		}
		if (missingOnly) {
			plans = plans.stream().filter(p -> store.find(p.slug()).isEmpty()).toList();
		}
		var written = new ArrayList<String>();
		var failed = new LinkedHashMap<String, String>();
		try (var pool = Executors.newFixedThreadPool(CONCURRENCY, Thread.ofVirtual().factory())) {
			Map<Plan, Future<?>> futures = new LinkedHashMap<>();
			plans.forEach(p -> futures.put(p, pool.submit(() -> write(p))));
			for (var e : futures.entrySet()) {
				try {
					e.getValue().get();
					written.add(e.getKey().slug());
				}
				catch (ExecutionException ex) {
					log.warn("wiki page {} failed: {}", e.getKey().slug(), ex.getCause().toString());
					failed.put(e.getKey().slug(), String.valueOf(ex.getCause()));
				}
			}
		}
		var r = new LinkedHashMap<String, Object>();
		r.put("planned", plans.size());
		r.put("written", written.size());
		r.put("failed", failed);
		return r;
	}

	private List<Plan> plans(JsonNode logged) {
		var out = new ArrayList<Plan>();
		for (JsonNode p : logged) {
			out.add(new Plan(p.path("slug").asString(), p.path("kind").asString(), p.path("title").asString(),
					p.path("sections").valueStream().map(JsonNode::asString).toList()));
		}
		return out;
	}

	List<Plan> plan(List<SourceSections.Section> outline) {
		Map<String, SourceSections.Section> byRef = outline.stream().collect(Collectors.toMap(SourceSections.Section::ref, s -> s, (a, b) -> a));
		String list = outline.stream().map(s -> s.ref() + " | " + s.heading() + " | " + s.chars()).collect(Collectors.joining("\n"));
		JsonNode out = ask(planPrompt, "<outline>\n" + list + "\n</outline>", 16_000);
		var plans = new ArrayList<Plan>();
		var slugs = new LinkedHashSet<String>();
		for (JsonNode p : out.path("pages")) {
			String slug = p.path("slug").asString("").replaceAll("[^a-z0-9-]", "");
			// 목록에 없는 참조는 버리고, 근거 글자 수 한도 안에서만
			var refs = new ArrayList<String>();
			int chars = 0;
			for (JsonNode r : p.path("sections")) {
				var s = byRef.get(r.asString());
				if (s != null && !refs.contains(s.ref()) && chars + s.chars() <= MAX_SOURCE_CHARS) {
					refs.add(s.ref());
					chars += s.chars();
				}
			}
			if (!slug.isEmpty() && !refs.isEmpty() && slugs.add(slug)) {
				plans.add(new Plan(slug, p.path("kind").asString("topic"), p.path("title").asString(slug), List.copyOf(refs)));
			}
		}
		return plans;
	}

	/** 페이지 하나: 근거 절 원문 → 작성 → 인용 검사 → 저장 (draft) */
	void write(Plan plan) {
		var texts = new LinkedHashMap<String, String>();
		plan.sections().forEach(ref -> sections.text(ref).ifPresent(t -> texts.put(ref, t)));
		var src = new StringBuilder("<kind>").append(plan.kind()).append("</kind>\n<title>").append(plan.title()).append("</title>\n<sources>\n");
		texts.forEach((ref, t) -> src.append("<source ref=\"").append(ref).append("\">\n").append(t).append("\n</source>\n"));
		String[] parsed = parsePage(generate(pagePrompt, src.append("</sources>").toString(), 6_000));
		String summary = parsed[0];
		String body = parsed[1];
		if (body.isEmpty()) {
			throw new IllegalStateException("empty body for " + plan.slug());
		}
		List<String> problems = problems(body, texts.keySet(), ref -> sections.text(ref).isPresent());
		var citations = new LinkedHashMap<String, String>();
		for (String ref : cited(body)) {
			sections.text(ref).ifPresent(t -> citations.put(ref, SourceSections.hash(t)));
		}
		var page = new WikiPage(plan.slug(), plan.kind(), plan.title(), summary, body, "draft", problems, List.copyOf(citations.keySet()));
		store.save(version, plan.slug(), plan.kind(), plan.title(), summary, body, problems, citations,
				embeddings.embed(plan.title() + "\n" + summary + "\n\n" + page.plainBody()));
		store.log("write", plan.slug(), Map.of("sources", texts.size(), "citations", citations.size(), "problems", problems.size()));
	}

	static List<String> cited(String body) {
		var out = new LinkedHashSet<String>();
		Matcher m = CITATION.matcher(body);
		while (m.find()) {
			out.add(m.group(1));
		}
		return List.copyOf(out);
	}

	/**
	 * 인용 검사 (코드가 한다): 주어진 근거 밖의 인용, 원본에 없는 인용, 인용 없는 문장·항목.
	 * 제목·코드 블록·빈 줄은 인용이 필요 없다.
	 */
	static List<String> problems(String body, Set<String> given, Function<String, Boolean> exists) {
		var out = new ArrayList<String>();
		for (String ref : cited(body)) {
			if (!given.contains(ref)) {
				out.add("근거로 주지 않은 절을 인용: " + ref);
			}
			else if (!exists.apply(ref)) {
				out.add("원본에 없는 절: " + ref);
			}
		}
		boolean code = false;
		for (String line : body.split("\n")) {
			String t = line.strip();
			if (t.startsWith("```")) {
				code = !code;
				continue;
			}
			if (code || t.isEmpty() || t.startsWith("#") || t.matches("[-|: ]+")) {
				continue;
			}
			if (!CITATION.matcher(t).find() && !t.endsWith(":")) {   // "다음과 같이 설정한다:"처럼 코드 블록을 여는 줄은 바로 아래가 근거
				out.add("인용 없는 줄: " + (t.length() > 80 ? t.substring(0, 80) + "…" : t));
			}
		}
		return out;
	}

	/** "SUMMARY: …" 첫 줄 + 마크다운 본문 → {요약, 본문}. 코드 울타리로 감쌌으면 벗긴다 */
	static String[] parsePage(String out) {
		String t = out.strip().replaceFirst("^```(?:markdown|md)?\\s*\\n", "").replaceFirst("\\n```\\s*$", "").strip();
		if (!t.startsWith("SUMMARY:")) {
			throw new IllegalStateException("page output has no SUMMARY line: " + t.substring(0, Math.min(120, t.length())));
		}
		int nl = t.indexOf('\n');
		String summary = (nl < 0 ? t : t.substring(0, nl)).substring("SUMMARY:".length()).strip();
		return new String[] { summary, nl < 0 ? "" : t.substring(nl + 1).strip() };
	}

	private JsonNode ask(String system, String user, int maxTokens) {
		String out = generate(system, user, maxTokens);
		int from = out.indexOf('{');
		int to = out.lastIndexOf('}');
		if (from < 0 || to < from) {
			throw new IllegalStateException("wiki model returned no JSON: " + out.substring(0, Math.min(200, out.length())));
		}
		return json.readTree(out.substring(from, to + 1));
	}

	/** 긴 생성은 스트리밍으로 받는다: 한 번에 받으면 몇 분 동안 연결이 놀다가 끊긴다 (계획 호출이 4분에 끊긴 적 있음) */
	private String generate(String system, String user, int maxTokens) {
		var text = new StringBuilder();
		try (var stream = chat.stream(new Prompt(List.of(new SystemMessage(system), new UserMessage(user)),
				LlmOptions.of(chat, model, maxTokens, Duration.ofMinutes(10)))).toStream()) {
			stream.forEach(r -> {
				String t = r.getResult() == null ? null : r.getResult().getOutput().getText();
				if (t != null) {
					text.append(t);
				}
			});
		}
		return text.toString();
	}

	private static String read(Resource r) throws java.io.IOException {
		return r.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "").strip();
	}
}
