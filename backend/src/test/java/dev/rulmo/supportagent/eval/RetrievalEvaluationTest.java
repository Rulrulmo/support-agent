package dev.rulmo.supportagent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.rulmo.supportagent.knowledge.SearchHit;

class RetrievalEvaluationTest {

	static SearchHit hit(String page, String... anchors) {
		return new SearchHit(1, "t", "h", "https://docs.test/gug/" + page + "#" + anchors[anchors.length - 1], List.of(anchors), "x", 0);
	}

	@Test
	void ranksTheFirstHitInTheExpectedSectionOrBelowIt() {
		var hits = List.of(hit("using-guacamole.html", "using", "clipboard"), hit("troubleshooting.html", "trouble", "it-isnt-working", "no-graphics-appear"));
		// 기대 절의 하위 절이 나와도 맞다
		assertThat(RetrievalEvaluation.rank(hits, List.of(new EvalSet.Source("troubleshooting.html", "it-isnt-working")))).isEqualTo(2);
		// 같은 절 id라도 다른 쪽이면 아니다
		assertThat(RetrievalEvaluation.rank(hits, List.of(new EvalSet.Source("administration.html", "clipboard")))).isNull();
	}

	@Test
	void summarizesHitRatesByLanguage() {
		var rs = List.of(new RetrievalEvaluation.Result("ko-1", "ko", "설치", "easy", 1), new RetrievalEvaluation.Result("ko-2", "ko", "설치", "hard", 7),
				new RetrievalEvaluation.Result("en-1", "en", "인증", "easy", null));
		@SuppressWarnings("unchecked")
		var ko = (Map<String, Object>) RetrievalEvaluation.summarize(rs).get("ko");
		assertThat(ko).containsEntry("n", 2).containsEntry("@1", 50.0).containsEntry("@5", 50.0).containsEntry("@10", 100.0);
		assertThat(RetrievalEvaluation.summarize(rs).get("misses@5")).isEqualTo(List.of("ko-2", "en-1"));
	}
}
