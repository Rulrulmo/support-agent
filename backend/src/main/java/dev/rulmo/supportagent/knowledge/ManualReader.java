package dev.rulmo.supportagent.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/**
 * 발행된 매뉴얼(Sphinx HTML) → 절 단위 글. 원본 마크다운에는 빌드 때 채워지는 템플릿(.md.j2)이 섞여 있어 발행본을 읽는다.
 * 절 = {@code <section id>}. 절의 글은 자기 몫만 (하위 절은 따로), 긴 절은 문단 경계로 나눈다.
 */
final class ManualReader {

	/** 절 하나 (긴 절이면 그 조각). anchors: 바깥 절부터 이 절까지의 id */
	record Section(List<String> anchors, String heading, String text) {
	}

	record Page(String path, String title, String url, List<Section> sections) {
	}

	private static final Pattern PAGE_LINK = Pattern.compile("[a-z0-9-]+\\.html");
	private static final List<String> NOT_PAGES = List.of("index.html", "genindex.html", "search.html");
	private static final int MAX_CHARS = 1800;
	private static final int MIN_CHARS = 80;   // 이보다 짧은 절(제목뿐 등)은 버린다: 제목은 하위 절의 경로에 남는다

	private final Function<String, String> fetch;
	private final String baseUrl;

	/** @param fetch 주소 → HTML (테스트는 미리 준비한 글을 준다) */
	ManualReader(Function<String, String> fetch, String baseUrl) {
		this.fetch = fetch;
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
	}

	/** 목차(index.html)에 링크된 쪽들을 목차 순서대로 */
	List<Page> read() {
		var paths = new LinkedHashSet<String>();
		for (Element a : Jsoup.parse(fetch.apply(baseUrl + "index.html")).select("a[href]")) {
			String href = a.attr("href").replaceFirst("#.*$", "");
			if (PAGE_LINK.matcher(href).matches() && !NOT_PAGES.contains(href)) {
				paths.add(href);
			}
		}
		var pages = new ArrayList<Page>();
		for (String path : paths) {
			pages.add(page(path, fetch.apply(baseUrl + path)));
		}
		return pages;
	}

	Page page(String path, String html) {
		var doc = Jsoup.parse(html);
		doc.select("a.headerlink, script, style, nav").remove();   // ¶ 링크·탐색 메뉴
		var sections = new ArrayList<Section>();
		String title = null;
		for (Element s : doc.select("section[id]")) {
			var anchors = new ArrayList<String>();
			var headings = new ArrayList<String>();
			for (Element e = s; e != null; e = e.parent()) {
				if (e.tagName().equals("section") && e.hasAttr("id")) {
					anchors.addFirst(e.id());
					headings.addFirst(heading(e));
				}
			}
			if (title == null) {
				title = headings.getFirst();
			}
			String text = ownText(s);
			if (text.length() >= MIN_CHARS) {
				String heading = String.join(" > ", headings);
				split(text).forEach(part -> sections.add(new Section(List.copyOf(anchors), heading, part)));
			}
		}
		return new Page(path, title == null ? path : title, baseUrl + path, sections);
	}

	private static String heading(Element section) {
		Element h = section.selectFirst("> h1, > h2, > h3, > h4, > h5, > h6");
		return h == null ? section.id() : h.text().strip();
	}

	/** 절 자기 몫의 글: 하위 절과 제목은 빼고, 코드는 줄바꿈 그대로, 표는 행마다 "칸 | 칸", 목록은 "- 항목" */
	static String ownText(Element section) {
		var out = new StringBuilder();
		for (Element child : section.children()) {
			if (child.tagName().equals("section") || child.tagName().matches("h[1-6]")) {
				continue;
			}
			block(child, out);
		}
		return out.toString().replaceAll("\n{3,}", "\n\n").strip();
	}

	private static void block(Element e, StringBuilder out) {
		switch (e.tagName()) {
			case "pre" -> out.append(e.wholeText().strip()).append("\n\n");
			case "table" -> {
				for (Element row : e.select("tr")) {
					out.append(String.join(" | ", row.select("th, td").eachText())).append('\n');
				}
				out.append('\n');
			}
			case "ul", "ol" -> {
				for (Element li : e.select("> li")) {
					out.append("- ").append(li.text()).append('\n');
				}
				out.append('\n');
			}
			case "div", "blockquote", "dl" -> {   // 감싸는 것(코드 블록·참고 상자·정의 목록): 안쪽 블록을 그대로
				if (e.children().isEmpty()) {
					out.append(e.text()).append("\n\n");
				}
				else {
					e.children().forEach(c -> block(c, out));
				}
			}
			default -> {
				String t = e.text().strip();
				if (!t.isEmpty()) {
					out.append(t).append("\n\n");
				}
			}
		}
	}

	/** 문단(빈 줄) 경계로 MAX_CHARS 이하 조각. 문단 하나가 넘치면 그 문단만 글자 수로 자른다 */
	static List<String> split(String text) {
		if (text.length() <= MAX_CHARS) {
			return List.of(text);
		}
		var parts = new ArrayList<String>();
		var cur = new StringBuilder();
		for (String para : text.split("\n\n")) {
			if (!cur.isEmpty() && cur.length() + para.length() + 2 > MAX_CHARS) {
				parts.add(cur.toString().strip());
				cur.setLength(0);
			}
			if (para.length() > MAX_CHARS) {
				for (int i = 0; i < para.length(); i += MAX_CHARS) {
					parts.add(para.substring(i, Math.min(para.length(), i + MAX_CHARS)));
				}
				continue;
			}
			cur.append(para).append("\n\n");
		}
		if (!cur.isEmpty()) {
			parts.add(cur.toString().strip());
		}
		return parts;
	}
}
