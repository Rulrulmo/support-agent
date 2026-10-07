package dev.rulmo.supportagent.eval;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.rulmo.supportagent.knowledge.KnowledgeSearch;

/** 평가 실행. 평가셋 경로는 설정(eval.file)에서만 받는다 */
@RestController
@RequestMapping("/api/eval")
class EvalController {

	private final RetrievalEvaluation retrieval;
	private final AnswerEvaluation answers;

	EvalController(RetrievalEvaluation retrieval, AnswerEvaluation answers) {
		this.retrieval = retrieval;
		this.answers = answers;
	}

	/** 답변 평가 (judge 채점). 문항·결과 전체를 돌려주므로 curl -o로 저장해서 본다. 전체 60문항 약 $2 */
	@PostMapping("/answers")
	Map<String, Object> answers(@RequestParam(defaultValue = "0") int sample) throws InterruptedException {
		return AnswerEvaluation.summarize(answers.run(sample));
	}

	@PostMapping("/retrieval")
	Map<String, Object> retrieval(@RequestParam(defaultValue = "HYBRID") KnowledgeSearch.Mode mode, @RequestParam(defaultValue = "0") int sample,
			@RequestParam(required = false) Double keywordWeight) {
		return RetrievalEvaluation.summarize(retrieval.run(mode, sample, keywordWeight));
	}
}
