package dev.rulmo.supportagent.agent;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 질문 하나에 답한다 (M2 확인용). 채팅·대화 이력은 M4 */
@RestController
@Validated
class AnswerController {

	private final AnswerService answers;

	AnswerController(AnswerService answers) {
		this.answers = answers;
	}

	record Ask(@NotBlank @Size(max = 2000) String question) {
	}

	@PostMapping("/api/answer")
	AnswerService.Answer ask(@Valid @RequestBody Ask req) {
		return answers.answer(req.question());
	}
}
