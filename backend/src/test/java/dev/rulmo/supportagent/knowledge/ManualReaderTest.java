package dev.rulmo.supportagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Sphinx 발행본 모양의 작은 HTML (직접 쓴 글)로 절 나누기를 확인한다 */
class ManualReaderTest {

	static final String INDEX = """
			<html><body><nav><a href="search.html">Search</a></nav>
			<a href="setup.html">Setup</a> <a href="setup.html#ports">Ports</a> <a href="genindex.html">Index</a> <a href="faq.html">FAQ</a>
			</body></html>""";

	static final String SETUP = """
			<html><body><div role="main">
			<section id="setup"><h1>Setup<a class="headerlink" href="#setup">¶</a></h1>
			  <p>This page explains how to install the demo gateway and which network ports it needs before the first login.</p>
			  <section id="ports"><h2>Ports</h2>
			    <p>The gateway daemon listens on port 4822 by default, and the web application is served by the servlet container.</p>
			    <div class="highlight"><pre>guacd -b 127.0.0.1 -l 4822
			guacd -f</pre></div>
			    <table><tr><th>Name</th><th>Default</th></tr><tr><td>guacd-port</td><td>4822</td></tr></table>
			    <ul><li>Open the port on the firewall</li><li>Restart the daemon</li></ul>
			  </section>
			  <section id="short"><h2>Short</h2><p>Too short.</p></section>
			</section></div></body></html>""";

	@Test
	void readsLinkedPagesInOrderAndSplitsBySection() {
		var html = Map.of("https://docs.test/gug/index.html", INDEX, "https://docs.test/gug/setup.html", SETUP,
				"https://docs.test/gug/faq.html", "<html><body><section id=\"faq\"><h1>FAQ</h1></section></body></html>");
		var pages = new ManualReader(html::get, "https://docs.test/gug").read();

		assertThat(pages).extracting(ManualReader.Page::path).containsExactly("setup.html", "faq.html");
		var setup = pages.getFirst();
		assertThat(setup.title()).isEqualTo("Setup");   // ¶ 링크는 뺀다
		assertThat(setup.sections()).hasSize(2);        // 짧은 절은 버린다
		var top = setup.sections().get(0);
		assertThat(top.text()).contains("install the demo gateway").doesNotContain("listens on port 4822");   // 하위 절은 따로
		var ports = setup.sections().get(1);
		assertThat(ports.anchors()).containsExactly("setup", "ports");
		assertThat(ports.heading()).isEqualTo("Setup > Ports");
		assertThat(ports.text()).contains("guacd -b 127.0.0.1 -l 4822\nguacd -f", "guacd-port | 4822", "- Open the port on the firewall");
	}

	@Test
	void splitsLongSectionsOnParagraphs() {
		String para = "word ".repeat(300).strip();   // 약 1500자
		var parts = ManualReader.split(para + "\n\n" + para + "\n\n" + "x".repeat(4000));
		assertThat(parts).hasSize(5);
		assertThat(parts).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(1800));
	}
}
