package dev.rulmo.supportagent.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** 인용 검사는 LLM이 아니라 코드가 한다 */
class WikiCompilerTest {

	@Test
	void flagsCitationsOutsideGivenSourcesMissingSectionsAndUncitedLines() {
		String body = """
				## 증상
				화면이 안 나온다 [[troubleshooting.html#no-graphics-appear]]

				## 해결
				1. HTTPS를 쓴다 [[troubleshooting.html#no-graphics-appear]]
				2. 프록시 버퍼링을 끈다
				다음과 같이 설정한다:
				```
				proxy_buffering off;
				```
				- 지어낸 절 [[troubleshooting.html#made-up]]
				- 안 준 절 [[ldap-auth.html#ldap-auth]]
				""";
		var problems = WikiCompiler.problems(body, Set.of("troubleshooting.html#no-graphics-appear", "troubleshooting.html#made-up"),
				ref -> !ref.endsWith("#made-up"));

		assertThat(problems).containsExactly("원본에 없는 절: troubleshooting.html#made-up", "근거로 주지 않은 절을 인용: ldap-auth.html#ldap-auth",
				"인용 없는 줄: 2. 프록시 버퍼링을 끈다");   // 제목·코드 블록·코드를 여는 줄(:)은 인용이 필요 없다
		assertThat(WikiCompiler.cited(body)).containsExactly("troubleshooting.html#no-graphics-appear", "troubleshooting.html#made-up",
				"ldap-auth.html#ldap-auth");
	}

	@Test
	void parsesSummaryLineAndBodyEvenInsideCodeFence() {
		assertThat(WikiCompiler.parsePage("SUMMARY: 요약 \"따옴표\"\n## 본문\n내용")).containsExactly("요약 \"따옴표\"", "## 본문\n내용");
		assertThat(WikiCompiler.parsePage("```markdown\nSUMMARY: s\nbody\n```")).containsExactly("s", "body");
	}

	@Test
	void plainBodyDropsCitations() {
		var page = new WikiPage("s", "topic", "t", "s", "프록시를 끈다 [[a.html#b]]\n- 항목 [[a.html#c]]", "draft", java.util.List.of(), java.util.List.of());
		assertThat(page.plainBody()).isEqualTo("프록시를 끈다\n- 항목");
	}
}
