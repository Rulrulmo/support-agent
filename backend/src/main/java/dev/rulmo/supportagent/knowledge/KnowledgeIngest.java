package dev.rulmo.supportagent.knowledge;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** 원본 수집 → 임베딩 → 저장. 바뀐 쪽만 다시 임베딩하고, 원본에서 사라진 쪽은 지운다 */
@Service
class KnowledgeIngest {

	private static final Logger log = LoggerFactory.getLogger(KnowledgeIngest.class);
	private static final int EMBED_BATCH = 64;

	private final KnowledgeStore store;
	private final EmbeddingModel embeddings;
	private final String source;
	private final String version;
	private final String baseUrl;
	private final Duration politeness;

	/** @param baseUrl 발행된 매뉴얼 (버전 고정). 원문은 저장소에 넣지 않고 여기서 받는다 (NOTICE) */
	KnowledgeIngest(KnowledgeStore store, EmbeddingModel embeddings, @Value("${knowledge.source.name}") String source,
			@Value("${knowledge.source.version}") String version, @Value("${knowledge.source.base-url}") String baseUrl,
			@Value("${knowledge.source.politeness:300ms}") Duration politeness) {
		this.store = store;
		this.embeddings = embeddings;
		this.source = source;
		this.version = version;
		this.baseUrl = baseUrl;
		this.politeness = politeness;
	}

	Map<String, Integer> ingest() {
		var http = RestClient.builder().defaultHeader("User-Agent", "support-agent (portfolio demo)").build();
		return ingest(new ManualReader(url -> {
			sleep(politeness);   // 공개 사이트 예의상 간격
			// 사이트가 charset을 안 알려 줘서 String으로 받으면 ISO-8859-1로 깨진다 ("isn’t" → "isnât"). Sphinx 출력은 UTF-8
			return new String(http.get().uri(url).retrieve().body(byte[].class), StandardCharsets.UTF_8);
		}, baseUrl).read());
	}

	Map<String, Integer> ingest(List<ManualReader.Page> pages) {
		int indexed = 0, unchanged = 0, chunks = 0;
		for (var page : pages) {
			String hash = KnowledgeStore.hash(page);
			if (store.unchanged(source, version, page, hash)) {
				unchanged++;
				continue;
			}
			List<String> texts = page.sections().stream().map(s -> s.heading() + "\n\n" + s.text()).toList();
			var vectors = new ArrayList<float[]>();
			for (int i = 0; i < texts.size(); i += EMBED_BATCH) {
				vectors.addAll(embeddings.embed(texts.subList(i, Math.min(texts.size(), i + EMBED_BATCH))));
			}
			store.replace(source, version, page, hash, vectors);
			indexed++;
			chunks += texts.size();
		}
		int deleted = pages.isEmpty() ? 0 : store.deleteMissing(source, version, pages.stream().map(ManualReader.Page::path).toList());
		log.info("ingest {} {}: pages {} (unchanged {}, deleted {}), chunks {}", source, version, indexed, unchanged, deleted, chunks);
		var r = new LinkedHashMap<String, Integer>();
		r.put("indexedPages", indexed);
		r.put("unchangedPages", unchanged);
		r.put("deletedPages", deleted);
		r.put("indexedChunks", chunks);
		return r;
	}

	private static void sleep(Duration d) {
		try {
			Thread.sleep(d);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
