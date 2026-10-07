package dev.rulmo.supportagent.eval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import dev.rulmo.supportagent.knowledge.KnowledgeSearch;
import dev.rulmo.supportagent.knowledge.SearchHit;

import tools.jackson.databind.json.JsonMapper;

/**
 * 검색 평가: 기대 근거 절이 검색 몇 위에 나오나. 결과 청크의 절이 기대 절이거나 그 하위 절이면 맞다 (anchors에 들어 있으면).
 * 근거가 없는 동작 문항(되묻기·이관·모름)은 뺀다.
 */
@Component
class RetrievalEvaluation {

	static final int K = 10;

	private final KnowledgeSearch search;
	private final JsonMapper json;
	private final Path file;

	RetrievalEvaluation(KnowledgeSearch search, JsonMapper json, @Value("${eval.file}") String file) {
		this.search = search;
		this.json = json;
		this.file = Path.of(file);
	}

	/** @param rank 1부터, 상위 K 밖이면 null */
	record Result(String id, String lang, String category, String difficulty, Integer rank) {
	}

	/** @param keywordWeight null이면 설정값 */
	List<Result> run(KnowledgeSearch.Mode mode, int sample, Double keywordWeight) {
		var out = new ArrayList<Result>();
		for (var it : EvalSet.load(json, file).sample(sample)) {
			if (it.sources().isEmpty()) {
				continue;
			}
			var hits = keywordWeight == null ? search.search(it.question(), K, mode) : search.search(it.question(), K, mode, keywordWeight);
			out.add(new Result(it.id(), it.lang(), it.category(), it.difficulty(), rank(hits, it.sources())));
		}
		return out;
	}

	static Integer rank(List<SearchHit> hits, List<EvalSet.Source> sources) {
		for (int i = 0; i < hits.size(); i++) {
			SearchHit h = hits.get(i);
			if (sources.stream().anyMatch(s -> h.url().contains("/" + s.page() + "#") && h.anchors().contains(s.anchor()))) {
				return i + 1;
			}
		}
		return null;
	}

	static Map<String, Object> summarize(List<Result> rs) {
		var out = new LinkedHashMap<String, Object>();
		out.put("all", stats(rs, r -> true));
		for (String lang : List.of("ko", "en")) {
			out.put(lang, stats(rs, r -> r.lang().equals(lang)));
		}
		var byCategory = new LinkedHashMap<String, Object>();
		rs.stream().map(Result::category).distinct().sorted().forEach(c -> byCategory.put(c, stats(rs, r -> r.category().equals(c))));
		out.put("category", byCategory);
		out.put("misses@5", rs.stream().filter(r -> r.rank() == null || r.rank() > 5).map(Result::id).toList());
		out.put("results", rs);
		return out;
	}

	private static Map<String, Object> stats(List<Result> all, Predicate<Result> f) {
		var rs = all.stream().filter(f).toList();
		double n = Math.max(1, rs.size());
		var s = new LinkedHashMap<String, Object>();
		s.put("n", rs.size());
		for (int k : new int[] { 1, 3, 5, 10 }) {
			s.put("@" + k, pct(rs.stream().filter(r -> r.rank() != null && r.rank() <= k).count() / n));
		}
		s.put("mrr", Math.round(rs.stream().mapToDouble(r -> r.rank() == null ? 0 : 1.0 / r.rank()).sum() / n * 1000) / 1000.0);
		return s;
	}

	private static double pct(double v) {
		return Math.round(v * 1000) / 10.0;
	}
}
