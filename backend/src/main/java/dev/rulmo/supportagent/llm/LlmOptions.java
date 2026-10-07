package dev.rulmo.supportagent.llm;

import java.time.Duration;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 요청 옵션: 모델의 기본 옵션을 복사해 용도 이름(LiteLLM)·길이·시간 한도만 바꾼다.
 * 시간 한도를 코드에서 넣는 이유: Spring AI 2.0.1은 spring.ai.openai.chat.options.timeout을 기본 옵션에 넣지 않아
 * 요청이 늘 60초·재시도 3번이다 → 긴 생성(위키 계획)이 끊기고, 재시도가 같은 생성을 다시 사서 비용만 는다 (2026-10-07 확인)
 */
public final class LlmOptions {

	private LlmOptions() {
	}

	/** @param maxTokens null이면 기본 */
	public static ChatOptions of(ChatModel chat, String model, Integer maxTokens, Duration timeout) {
		var b = chat.getOptions().mutate().model(model);
		if (maxTokens != null) {
			b.maxTokens(maxTokens);
		}
		if (b instanceof OpenAiChatOptions.Builder o) {   // 테스트용 가짜 모델은 OpenAI 옵션이 아니다
			o.timeout(timeout).maxRetries(1);
		}
		return b.build();
	}
}
