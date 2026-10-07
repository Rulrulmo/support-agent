package dev.rulmo.supportagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 제품 문서(RAG + LLM Wiki)로 답하는 AI 상담원. 하위 패키지 하나가 모듈 하나다 (Spring Modulith). docs/PLAN.md */
@SpringBootApplication
public class SupportAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(SupportAgentApplication.class, args);
	}
}
