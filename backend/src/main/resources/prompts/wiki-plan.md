<!--
LLM Wiki 스키마 ①: 페이지 계획 (wiki.WikiCompiler.plan). 원본 절 목록을 보고 만들 페이지와 근거 절을 정한다.
출력 JSON 모양을 바꾸면 WikiCompiler 파싱도 바꾼다. 이 주석은 모델에 보내지 않는다.
-->
너는 Apache Guacamole 1.6.0 매뉴얼로 고객 상담용 **한국어 위키**를 설계한다. <outline>은 원본 절 목록이다 (참조 | 제목 경로 | 글자 수).

[페이지 종류]
- troubleshooting: 사용자가 겪는 **증상** 하나(또는 아주 비슷한 증상 묶음) → 원인 → 확인 → 해결. 제목은 사용자가 상담 채팅에 쓸 법한 증상으로 (예: "접속하면 'waiting for first update'만 뜨고 끊겨요").
- topic: 기능·설정 하나 (무엇인지, 언제 쓰는지, 설정 방법).
- reference: 자주 찾는 설정 키·연결 파라미터 묶음.

[규칙]
- 사용자·관리자가 상담에서 물을 만한 것을 고른다. 개발자용(프로토콜 내부, API, 확장 개발)은 뺀다.
- troubleshooting을 가장 많이 만든다: 매뉴얼의 Troubleshooting 장뿐 아니라, 설정·설치·인증 장에서 "이걸 안 하면 어떤 증상이 생기는지"가 드러나는 것도 증상 페이지로 만든다 (예: 로그인 실패가 반복되면 일정 시간 막히는 기능).
- 페이지마다 근거 절 참조 1~12개. <outline>에 있는 참조만 쓰고, 근거 절 글자 수 합은 25,000자 이하로 한다. 원인·해결이 다른 장에 있으면 그 절도 넣는다. 한 절을 여러 페이지에 써도 된다.
- 40~60쪽. slug는 영어 소문자와 하이픈, title은 한국어.

JSON 객체 하나만 출력한다:
{"pages":[{"slug":"no-graphics-waiting-for-first-update","kind":"troubleshooting","title":"...","sections":["troubleshooting.html#no-graphics-appear"]}]}
