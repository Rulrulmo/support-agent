package dev.rulmo.supportagent.knowledge;

import java.util.List;

/**
 * 검색 결과 청크 하나 (원본 절). 답변의 출처 표시에 쓴다.
 *
 * @param url     절로 바로 가는 주소 (쪽 주소#절 id)
 * @param anchors 바깥 절부터 이 절까지의 id
 */
public record SearchHit(long chunkId, String pageTitle, String heading, String url, List<String> anchors, String text,
		double score) {
}
