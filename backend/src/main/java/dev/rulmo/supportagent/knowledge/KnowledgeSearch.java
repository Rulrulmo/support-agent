package dev.rulmo.supportagent.knowledge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 원본 검색: 영어 전문 검색 + 벡터를 RRF로 합친다.
 * 원본이 영어라 한국어 질문은 키워드 쪽에서 영어 낱말(RDP, LDAP, guacd …)만 쓰이고, 뜻은 벡터 쪽이 맞춘다.
 */
@Service
public class KnowledgeSearch {

	private static final int CANDIDATES = 40;
	private static final int RRF_K = 60;
	private static final Pattern ASCII_TERM = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*[A-Za-z0-9]|[A-Za-z0-9]");

	/** 검색 방식: 평가·원인 분석용으로 한쪽만 쓸 수 있다 */
	public enum Mode { KEYWORD, VECTOR, HYBRID }

	private final KnowledgeStore store;
	private final EmbeddingModel embeddings;
	private final double keywordWeight;

	/** @param keywordWeight 하이브리드에서 키워드 순위의 비중 (벡터 = 1). 평가셋으로 맞춘다 */
	KnowledgeSearch(KnowledgeStore store, EmbeddingModel embeddings, @Value("${knowledge.search.keyword-weight:1.0}") double keywordWeight) {
		this.store = store;
		this.embeddings = embeddings;
		this.keywordWeight = keywordWeight;
	}

	public List<SearchHit> search(String query, int k) {
		return search(query, k, Mode.HYBRID);
	}

	public List<SearchHit> search(String query, int k, Mode mode) {
		List<Long> keyword = mode == Mode.VECTOR ? List.of() : store.keyword(terms(query), CANDIDATES);
		List<Long> vector = mode == Mode.KEYWORD ? List.of() : store.vector(embeddings.embed(query), CANDIDATES);
		Map<Long, Double> score = new HashMap<>();
		for (int i = 0; i < keyword.size(); i++) {
			score.merge(keyword.get(i), keywordWeight / (RRF_K + i + 1), Double::sum);
		}
		for (int i = 0; i < vector.size(); i++) {
			score.merge(vector.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
		}
		var ranked = new ArrayList<>(score.keySet());
		ranked.sort((a, b) -> Double.compare(score.get(b), score.get(a)));
		return store.load(ranked.subList(0, Math.min(k, ranked.size())), score);
	}

	/** 질문 속 영어·숫자 낱말 (소문자). 한글은 영어 원본과 키워드로 맞지 않아 뺀다 */
	static List<String> terms(String query) {
		var out = new LinkedHashSet<String>();
		var m = ASCII_TERM.matcher(query);
		while (m.find()) {
			out.add(m.group().toLowerCase());
		}
		return List.copyOf(out);
	}

	public long size() {
		return store.count();
	}
}
