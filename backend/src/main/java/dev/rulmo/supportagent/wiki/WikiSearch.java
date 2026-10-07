package dev.rulmo.supportagent.wiki;

import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/** 위키 페이지 검색 (답변 턴이 원본보다 먼저 본다). 어떤 상태까지 쓸지는 부르는 쪽이 정한다: 운영은 published만 */
@Service
public class WikiSearch {

	/** published만 (운영) / 검수 전 draft까지 (평가 실험용, 결과에 반드시 "검수 전"이라고 적는다) */
	public enum Use { NONE, PUBLISHED, UNREVIEWED }

	private final WikiStore store;
	private final EmbeddingModel embeddings;

	WikiSearch(WikiStore store, EmbeddingModel embeddings) {
		this.store = store;
		this.embeddings = embeddings;
	}

	public List<WikiPage> search(String query, int k, Use use) {
		return switch (use) {
			case NONE -> List.of();
			case PUBLISHED -> store.search(embeddings.embed(query), List.of("published"), k);
			case UNREVIEWED -> store.search(embeddings.embed(query), List.of("published", "draft"), k);
		};
	}
}
