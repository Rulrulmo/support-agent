package dev.rulmo.supportagent.wiki;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 위키 운영: 컴파일·점검·목록·검수. 서버가 127.0.0.1에만 열려 인증 없음 — 콘솔(M4)이 이 API를 쓴다 */
@RestController
@RequestMapping("/api/wiki")
@Validated
class WikiController {

	private final WikiCompiler compiler;
	private final WikiLint lint;
	private final WikiStore store;

	WikiController(WikiCompiler compiler, WikiLint lint, WikiStore store) {
		this.compiler = compiler;
		this.lint = lint;
		this.store = store;
	}

	/** 원본 → 페이지 계획 → 작성 (draft). 수십 번 LLM을 부른다. replan=false면 마지막 계획으로 페이지만 다시 쓴다 */
	@PostMapping("/compile")
	Map<String, Object> compile(@RequestParam(defaultValue = "true") boolean replan, @RequestParam(defaultValue = "false") boolean missingOnly)
			throws InterruptedException {
		return compiler.compile(replan, missingOnly);
	}

	@PostMapping("/lint")
	Map<String, Object> lint() {
		return lint.lint();
	}

	@GetMapping("/pages")
	List<WikiPage> pages(@RequestParam(defaultValue = "draft,published,stale,rejected") List<String> status) {
		return store.list(status);
	}

	@GetMapping("/pages/{slug}")
	WikiPage page(@PathVariable String slug) {
		return store.find(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	record Review(@Pattern(regexp = "publish|reject") String decision, @Size(max = 1000) String note) {
	}

	/** 검수: 게시하거나 반려 (반려 사유는 다시 컴파일할 때 참고) */
	@PostMapping("/pages/{slug}/review")
	WikiPage review(@PathVariable String slug, @Valid @RequestBody Review req) {
		if (!store.setStatus(slug, "publish".equals(req.decision()) ? "published" : "rejected", req.note())) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		store.log("review", slug, Map.of("decision", req.decision()));
		return store.find(slug).orElseThrow();
	}

	/** 목차: 종류별 페이지 제목·요약 (원형의 index.md) */
	@GetMapping("/index")
	Map<String, Object> index() {
		var byKind = store.list(List.of("published", "draft")).stream()
			.collect(Collectors.groupingBy(WikiPage::kind, LinkedHashMap::new,
					Collectors.mapping(p -> Map.of("slug", p.slug(), "title", p.title(), "summary", p.summary(), "status", p.status()), Collectors.toList())));
		return new LinkedHashMap<>(byKind);
	}
}
