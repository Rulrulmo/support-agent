package dev.rulmo.supportagent.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 평가셋 (docs/eval/v0.jsonl): 한 줄에 한 문항. 형식은 docs/eval/README.md */
record EvalSet(List<Item> items) {

	/** @param sources 기대 근거 절 (쪽, 절 id). 동작 문항(되묻기·이관·모름)은 비어 있을 수 있다 */
	record Item(String id, String lang, String question, String category, String difficulty, List<String> keyPoints,
			List<String> mustNot, List<Source> sources, String expectedBehavior) {
	}

	record Source(String page, String anchor) {
	}

	static EvalSet load(JsonMapper json, Path file) {
		try {
			var items = new ArrayList<Item>();
			for (String line : Files.readAllLines(file)) {
				if (line.isBlank()) {
					continue;
				}
				JsonNode n = json.readTree(line);
				items.add(new Item(n.path("id").asString(), n.path("lang").asString(), n.path("question").asString(),
						n.path("category").asString(""), n.path("difficulty").asString(""), strings(n.path("expected_key_points")),
						strings(n.path("must_not")), n.path("sources").valueStream()
							.map(s -> new Source(s.path("page").asString(), s.path("anchor").asString())).toList(),
						n.path("expected_behavior").asString("answer")));
			}
			return new EvalSet(List.copyOf(items));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 한·영 비율을 유지하며 고르게 n개 (0이면 전체) */
	List<Item> sample(int n) {
		if (n <= 0 || n >= items.size()) {
			return items;
		}
		var out = new ArrayList<Item>();
		for (int i = 0; i < n; i++) {
			out.add(items.get(i * items.size() / n));
		}
		return out;
	}

	private static List<String> strings(JsonNode array) {
		return array.valueStream().map(JsonNode::asString).toList();
	}
}
