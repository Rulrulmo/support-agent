package dev.rulmo.supportagent.knowledge;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 운영용 API. 수집 대상은 설정에서만 받는다 (요청으로 임의 주소를 읽지 않게). 인증 없음: 서버는 127.0.0.1에만 열린다 */
@RestController
@RequestMapping("/api/knowledge")
class KnowledgeController {

	private final KnowledgeIngest ingest;
	private final KnowledgeSearch search;

	KnowledgeController(KnowledgeIngest ingest, KnowledgeSearch search) {
		this.ingest = ingest;
		this.search = search;
	}

	@PostMapping("/ingest")
	Map<String, Integer> ingest() {
		return ingest.ingest();
	}

	@GetMapping("/search")
	List<SearchHit> search(@RequestParam String q, @RequestParam(defaultValue = "5") int k,
			@RequestParam(defaultValue = "HYBRID") KnowledgeSearch.Mode mode) {
		return search.search(q, Math.clamp(k, 1, 20), mode);
	}

	@GetMapping("/stats")
	Map<String, Long> stats() {
		return Map.of("chunks", search.size());
	}
}
