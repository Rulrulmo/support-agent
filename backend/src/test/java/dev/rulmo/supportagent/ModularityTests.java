package dev.rulmo.supportagent;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** 모듈(하위 패키지) 사이 의존 규칙: 다른 모듈의 내부를 직접 쓰지 않는다, 순환 없음 (DB·Docker 불필요) */
class ModularityTests {

	@Test
	void verifiesModuleBoundaries() {
		ApplicationModules.of(SupportAgentApplication.class).verify();
	}
}
