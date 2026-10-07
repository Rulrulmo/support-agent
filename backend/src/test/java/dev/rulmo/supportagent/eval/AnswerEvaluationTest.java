package dev.rulmo.supportagent.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.io.ClassPathResource;

import dev.rulmo.supportagent.agent.AnswerService;

import tools.jackson.databind.json.JsonMapper;

/** judge 판정 → 정답·환각·동작 집계 (답변·judge 모두 가짜) */
class AnswerEvaluationTest {

	@TempDir
	Path dir;

	@Test
	@SuppressWarnings("unchecked")
	void scoresFromJudgeVerdicts() throws Exception {
		Path file = dir.resolve("v0.jsonl");
		Files.writeString(file, """
				{"id":"ko-1","lang":"ko","question":"Q1","category":"설치","difficulty":"easy","expected_key_points":["a","b"],"must_not":["m"],"sources":[],"expected_behavior":"answer"}
				{"id":"ko-2","lang":"ko","question":"Q2","category":"설치","difficulty":"easy","expected_key_points":["a"],"must_not":[],"sources":[],"expected_behavior":"clarify"}
				{"id":"en-1","lang":"en","question":"Q3","category":"인증","difficulty":"hard","expected_key_points":["a"],"must_not":[],"sources":[],"expected_behavior":"answer"}
				""");
		var answers = mock(AnswerService.class);
		when(answers.answer(anyString())).thenAnswer(inv -> new AnswerService.Answer("답 " + inv.getArgument(0), List.of(), List.of(), 1200));
		var verdicts = Map.of(
				"Q1", "```json\n{\"key_points\":[true,true],\"must_not\":[],\"behavior\":\"answer\",\"unsupported\":[]}\n```",   // 코드 울타리도 읽는다
				"Q2", "{\"key_points\":[true],\"must_not\":[],\"behavior\":\"answer\",\"unsupported\":[{\"claim\":\"지어낸 값\"}]}",   // 되묻지 않았고 환각
				"Q3", "판정할 수 없습니다");   // JSON 없음 → 오류로 빠진다
		ChatModel judge = p -> new ChatResponse(List.of(new Generation(new AssistantMessage(verdicts.entrySet().stream()
			.filter(e -> p.getUserMessage().getText().contains("<question>\n" + e.getKey() + "\n")).findFirst().orElseThrow().getValue()))));

		var eval = new AnswerEvaluation(answers, judge, JsonMapper.builder().build(), file.toString(), "judge", new ClassPathResource("prompts/judge.md"));
		var s = AnswerEvaluation.summarize(eval.run(0));

		assertThat(s.get("all")).isEqualTo(Map.of("n", 2, "correct%", 100.0, "keyPoints%", 100.0, "hallucination%", 50.0, "behavior%", 50.0));
		assertThat(s.get("hallucinated")).isEqualTo(List.of("ko-2"));
		assertThat((List<String>) s.get("errors")).singleElement().asString().startsWith("en-1:");
	}
}
