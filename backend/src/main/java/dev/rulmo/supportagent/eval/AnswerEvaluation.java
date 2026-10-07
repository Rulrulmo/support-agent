package dev.rulmo.supportagent.eval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import dev.rulmo.supportagent.agent.AnswerService;
import dev.rulmo.supportagent.knowledge.SearchHit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 답변 평가: 문항마다 답하고, judge(답변과 다른 벤더)가 요점·금지·동작·근거 없는 주장을 판정한다.
 * 정답 여부는 judge가 아니라 코드가 정한다: 요점을 전부 전하고 금지 사항을 어기지 않았으면 정답.
 */
@Component
class AnswerEvaluation {

	private static final int CONCURRENCY = 4;

	private final AnswerService answers;
	private final ChatModel chat;
	private final JsonMapper json;
	private final Path file;
	private final String judgeModel;
	private final String judgePrompt;

	AnswerEvaluation(AnswerService answers, ChatModel chat, JsonMapper json, @Value("${eval.file}") String file,
			@Value("${eval.judge-model:judge}") String judgeModel, @Value("classpath:prompts/judge.md") Resource judgePrompt) throws java.io.IOException {
		this.answers = answers;
		this.chat = chat;
		this.json = json;
		this.file = Path.of(file);
		this.judgeModel = judgeModel;
		this.judgePrompt = judgePrompt.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "").strip();
	}

	/** @param error 답변·채점이 실패하면 사유 (지표에서 뺀다) */
	record Result(String id, String lang, String category, String difficulty, String expectedBehavior, String behavior, int keyPoints,
			int keyPointsMet, List<String> mustNotViolated, List<String> unsupported, long latencyMs, String answer, List<String> cited,
			String reason, String error) {

		boolean correct() {
			return error == null && keyPointsMet == keyPoints && mustNotViolated.isEmpty();
		}

		boolean behaviorMatches() {
			return expectedBehavior.equals(behavior);
		}
	}

	List<Result> run(int sample) throws InterruptedException {
		var items = EvalSet.load(json, file).sample(sample);
		try (var pool = Executors.newFixedThreadPool(CONCURRENCY, Thread.ofVirtual().factory())) {
			List<Future<Result>> futures = items.stream().map(it -> pool.submit(() -> evaluate(it))).toList();
			var out = new ArrayList<Result>();
			for (var f : futures) {
				try {
					out.add(f.get());
				}
				catch (ExecutionException e) {
					throw new IllegalStateException(e.getCause());
				}
			}
			return out;
		}
	}

	Result evaluate(EvalSet.Item it) {
		try {
			var a = answers.answer(it.question());
			JsonNode v = judge(it, a.text(), a.context());
			int met = 0;
			for (int i = 0; i < it.keyPoints().size(); i++) {
				met += v.path("key_points").path(i).asBoolean(false) ? 1 : 0;
			}
			return new Result(it.id(), it.lang(), it.category(), it.difficulty(), it.expectedBehavior(), v.path("behavior").asString(""),
					it.keyPoints().size(), met, strings(v.path("must_not")), strings(v.path("unsupported")), a.latencyMs(), a.text(),
					a.cited().stream().map(SearchHit::url).toList(), v.path("reason").asString(""), null);
		}
		catch (RuntimeException e) {
			return new Result(it.id(), it.lang(), it.category(), it.difficulty(), it.expectedBehavior(), "", it.keyPoints().size(), 0,
					List.of(), List.of(), 0, "", List.of(), "", String.valueOf(e));
		}
	}

	private JsonNode judge(EvalSet.Item it, String answer, List<SearchHit> context) {
		var sb = new StringBuilder("<question>\n").append(it.question()).append("\n</question>\n<expected_key_points>\n");
		for (int i = 0; i < it.keyPoints().size(); i++) {
			sb.append(i + 1).append(". ").append(it.keyPoints().get(i)).append('\n');
		}
		sb.append("</expected_key_points>\n<must_not>\n");
		it.mustNot().forEach(m -> sb.append("- ").append(m).append('\n'));
		sb.append("</must_not>\n").append(AnswerService.documents(context)).append("\n<answer>\n").append(answer).append("\n</answer>");
		String out = chat.call(new Prompt(List.of(new SystemMessage(judgePrompt), new UserMessage(sb.toString())),
				chat.getOptions().mutate().model(judgeModel).build())).getResult().getOutput().getText();
		int from = out == null ? -1 : out.indexOf('{');
		int to = out == null ? -1 : out.lastIndexOf('}');
		if (from < 0 || to < from) {
			throw new IllegalStateException("judge returned no JSON: " + out);
		}
		return json.readTree(out.substring(from, to + 1));
	}

	/** judge가 목록 항목을 객체로 줘도 오류로 빠지지 않게 글로 받는다 (빠지면 환각 문항만 골라 빠진다) */
	private static List<String> strings(JsonNode array) {
		return array.valueStream().map(n -> n.isContainer() ? n.toString() : n.asString()).toList();
	}

	static Map<String, Object> summarize(List<Result> all) {
		var ok = all.stream().filter(r -> r.error() == null).toList();
		var out = new LinkedHashMap<String, Object>();
		out.put("all", stats(ok, r -> true));
		for (String lang : List.of("ko", "en")) {
			out.put(lang, stats(ok, r -> r.lang().equals(lang)));
		}
		var byBehavior = new LinkedHashMap<String, Object>();
		ok.stream().map(Result::expectedBehavior).distinct().sorted().forEach(b -> byBehavior.put(b, stats(ok, r -> r.expectedBehavior().equals(b))));
		out.put("expectedBehavior", byBehavior);
		var byCategory = new LinkedHashMap<String, Object>();
		ok.stream().map(Result::category).distinct().sorted().forEach(c -> byCategory.put(c, stats(ok, r -> r.category().equals(c))));
		out.put("category", byCategory);
		long[] latency = ok.stream().mapToLong(Result::latencyMs).sorted().toArray();
		out.put("latencyMs", latency.length == 0 ? Map.of() : Map.of("p50", latency[latency.length / 2], "p90", latency[(int) (latency.length * 0.9)]));
		out.put("wrong", ok.stream().filter(r -> !r.correct()).map(Result::id).toList());
		out.put("hallucinated", ok.stream().filter(r -> !r.unsupported().isEmpty()).map(Result::id).toList());
		out.put("errors", all.stream().filter(r -> r.error() != null).map(r -> r.id() + ": " + r.error()).toList());
		out.put("results", all);
		return out;
	}

	private static Map<String, Object> stats(List<Result> all, Predicate<Result> f) {
		var rs = all.stream().filter(f).toList();
		double n = Math.max(1, rs.size());
		double points = Math.max(1, rs.stream().mapToInt(Result::keyPoints).sum());
		var s = new LinkedHashMap<String, Object>();
		s.put("n", rs.size());
		s.put("correct%", pct(rs.stream().filter(Result::correct).count() / n));
		s.put("keyPoints%", pct(rs.stream().mapToInt(Result::keyPointsMet).sum() / points));
		s.put("hallucination%", pct(rs.stream().filter(r -> !r.unsupported().isEmpty()).count() / n));
		s.put("behavior%", pct(rs.stream().filter(Result::behaviorMatches).count() / n));
		return s;
	}

	private static double pct(double v) {
		return Math.round(v * 1000) / 10.0;
	}
}
