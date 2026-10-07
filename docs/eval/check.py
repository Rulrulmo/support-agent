"""평가셋 검증: 형식·값 목록·중복 질문, 그리고 근거 절(anchor)이 발행된 매뉴얼에 실제로 있는지.
실행: python3 docs/eval/check.py [docs/eval/v0.jsonl]   (네트워크 필요, 쪽마다 한 번 받는다)
"""
import json, sys, urllib.request

BASE = "https://guacamole.apache.org/doc/1.6.0/gug/"
FIELDS = {"id", "lang", "question", "category", "difficulty", "expected_key_points", "must_not", "sources", "expected_behavior"}
ALLOWED = {
    "lang": {"ko", "en"},
    "difficulty": {"easy", "medium", "hard"},
    "expected_behavior": {"answer", "clarify", "handoff", "refuse_unknown"},
    "category": {"설치", "설정", "인증", "연결·화면", "사용법", "관리", "보안", "문제해결", "기타"},
}

path = sys.argv[1] if len(sys.argv) > 1 else "docs/eval/v0.jsonl"
items = [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]
errors, pages = [], {}
for it in items:
    if missing := FIELDS - it.keys():
        errors.append(f"{it.get('id')}: 빠진 필드 {missing}")
    for field, allowed in ALLOWED.items():
        if it.get(field) not in allowed:
            errors.append(f"{it['id']}: {field}={it.get(field)!r}")
    if it["expected_behavior"] == "answer" and not it["sources"]:
        errors.append(f"{it['id']}: answer 문항인데 근거 절이 없다")
    for s in it["sources"]:
        if s["page"] not in pages:
            pages[s["page"]] = urllib.request.urlopen(BASE + s["page"], timeout=30).read().decode("utf-8")
        if f'id="{s["anchor"]}"' not in pages[s["page"]]:
            errors.append(f"{it['id']}: {s['page']}#{s['anchor']} 가 매뉴얼에 없다")
ids = [it["id"] for it in items]
questions = [it["question"] for it in items]
errors += [f"중복 id {x}" for x in set(ids) if ids.count(x) > 1] + [f"중복 질문 {x}" for x in set(questions) if questions.count(x) > 1]

print(f"{len(items)}문항 (ko {sum(it['lang'] == 'ko' for it in items)} · en {sum(it['lang'] == 'en' for it in items)}), 근거 절 쪽 {len(pages)}개 확인")
print("\n".join(errors) if errors else "OK")
sys.exit(1 if errors else 0)
