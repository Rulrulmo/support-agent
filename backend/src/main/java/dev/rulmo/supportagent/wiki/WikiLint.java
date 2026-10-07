package dev.rulmo.supportagent.wiki;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import dev.rulmo.supportagent.knowledge.SourceSections;

/**
 * 위키 점검: 인용한 원본 절이 사라졌거나 내용이 바뀐 페이지를 stale로 (다시 컴파일·검수 대상).
 * ponytail: 페이지 사이 모순·중복은 아직 안 본다 (LLM으로 목차를 훑는 단계, 페이지가 늘면)
 */
@Service
class WikiLint {

	private final WikiStore store;
	private final SourceSections sections;

	WikiLint(WikiStore store, SourceSections sections) {
		this.store = store;
		this.sections = sections;
	}

	Map<String, Object> lint() {
		var stale = new LinkedHashMap<String, Object>();
		store.citations().forEach((slug, cited) -> {
			var problems = new ArrayList<String>();
			cited.forEach((ref, hash) -> sections.text(ref).ifPresentOrElse(
					t -> {
						if (!SourceSections.hash(t).equals(hash)) {
							problems.add("원본이 바뀜: " + ref);
						}
					},
					() -> problems.add("원본에서 사라짐: " + ref)));
			if (!problems.isEmpty()) {
				store.markStale(slug, problems);
				stale.put(slug, problems);
			}
		});
		store.log("lint", null, Map.of("stale", stale.size()));
		return Map.of("checked", store.citations().size(), "stale", stale);
	}
}
