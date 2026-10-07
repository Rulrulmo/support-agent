package dev.rulmo.supportagent.wiki;

import java.util.List;

/**
 * 위키 페이지 하나.
 *
 * @param status    draft(검수 전) | published | rejected | stale(근거 원본이 바뀜)
 * @param problems  인용 검사·린트가 찾은 문제 (검수자가 본다)
 * @param citedRefs 본문이 인용한 원본 절 ("쪽.html#절id")
 */
public record WikiPage(String slug, String kind, String title, String summary, String body, String status, List<String> problems,
		List<String> citedRefs) {

	/** 답변 모델에 줄 본문: 인용 표시를 뺀다 (모델은 페이지 자체를 [n]으로 인용하고, 원본 출처는 citedRefs로 잇는다) */
	public String plainBody() {
		return WikiCompiler.CITATION.matcher(body).replaceAll("").replaceAll("[ \\t]+\\n", "\n").strip();
	}
}
