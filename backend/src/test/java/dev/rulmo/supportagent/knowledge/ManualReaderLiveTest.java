package dev.rulmo.supportagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.IntSummaryStatistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

/** 실제 발행 매뉴얼을 읽어 본다 (네트워크 필요, LLM 불필요). 실행: LIVE=1 ./gradlew test --tests '*ManualReaderLiveTest' -i */
@EnabledIfEnvironmentVariable(named = "LIVE", matches = "1")
class ManualReaderLiveTest {

	@Test
	void readsPublishedManual() {
		var http = RestClient.create();
		var pages = new ManualReader(url -> new String(http.get().uri(url).retrieve().body(byte[].class), java.nio.charset.StandardCharsets.UTF_8),
				"https://guacamole.apache.org/doc/1.6.0/gug/").read();
		IntSummaryStatistics sizes = pages.stream().flatMap(p -> p.sections().stream()).mapToInt(s -> s.text().length()).summaryStatistics();
		System.out.printf("pages %d, sections %d, chars avg %.0f max %d%n", pages.size(), sizes.getCount(), sizes.getAverage(), sizes.getMax());
		pages.forEach(p -> System.out.printf("  %-36s %3d  %s%n", p.path(), p.sections().size(), p.title()));
		var trouble = pages.stream().filter(p -> p.path().equals("troubleshooting.html")).findFirst().orElseThrow();
		trouble.sections().stream().limit(3).forEach(s -> System.out.printf("  [%s] %s%n    %s%n", String.join("/", s.anchors()), s.heading(),
				s.text().substring(0, Math.min(160, s.text().length())).replace("\n", " ")));
		assertThat(pages).hasSizeGreaterThan(40);
		assertThat(trouble.sections()).anySatisfy(s -> assertThat(s.anchors()).contains("no-graphics-appear"));
		assertThat(trouble.sections().getFirst().heading()).contains("isn’t");   // UTF-8로 읽었다
	}
}
