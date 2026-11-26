# 게임 서버 API·트랜잭션 함수 구현

상태(2026-11-23): 실제 Supabase DB 구성·기본 금융 문항 30개 등록·Edge Function 배포 완료. Android debug 화면에서 실제 사용자 인증을 거쳐 게임 생성과 선공 결정 성공을 확인했습니다. 이후 실제 AI 주사위 2·채권 칸 이동·AI 730G·퀴즈 발급도 화면으로 확인했습니다. 새 퀴즈 판정 기능은 로컬 검증 완료이며 원격 적용 전입니다.

## 파일과 역할

- docs/api/game-db-draft.sql: 게임 상태·RLS 기본 구조, 테이블 7개.
- docs/api/quiz-db-draft.sql: 문항 원본·버전·비공개 정답·출처 테이블 5개.
- docs/api/game-rpc-draft.sql: 정규화된 문항 저장소를 사용하는 서버 전용 DB 함수.
- supabase/functions/finpop-api/index.ts: Supabase Edge Function 진입점.
- supabase/functions/finpop-api/handler.mjs: Auth 검증과 HTTP 요청·응답 처리.
- tools/db-schema/validate-rpc.mjs: 기본 스키마·문항 검수·RPC 통합 검증.
- tools/db-schema/handler.test.mjs: 인증·HTTP 요청·배포 경로 검증 24개.

서버 파일은 Android app 모듈과 다른 런타임입니다.
일반 app의 Google 로그인 이후 ServerGameBoardRoute와 RemoteGameRepository가 팀원 보드·퀴즈 팝업을 서버에 연결합니다. 임시 debug 테스트 화면은 삭제했습니다.
DB 함수 SQL은 docs/api에 보관합니다. game-quiz-setup.sql과 basic-finance-30-import.sql은 대시보드에서 수동 적용했으며, 기존 사용자·캐릭터 테이블은 유지했습니다. 이미 적용한 프로젝트에 생성·등록 SQL을 다시 실행하지 않습니다.

## 지원 API

기준 URL: https://xzxbhbdartmcyvoslwsl.supabase.co/functions/v1/finpop-api

| 메서드 | 경로 | 처리 |
|---|---|---|
| GET | /v1/game-config | 규칙 1.0과 14칸 정의 |
| POST | /v1/games | 보유 캐릭터·레벨·완료 횟수 검증, 양측 700G·7턴 생성 |
| GET | /v1/games/{gameId} | 본인 게임 상태 조회 |
| POST | /v1/games/{gameId}/initiative | 동률 재추첨·선공·현재 차례 생성 |
| POST | /v1/games/{gameId}/purchases | 사용자 구매·패스 |
| POST | /v1/games/{gameId}/ai/purchases | AI 구매·패스 결정 |
| POST | /v1/games/{gameId}/turn-completions | 사용자 차례 종료 |
| POST | /v1/games/{gameId}/ai/turn-completions | AI 차례 종료 |
| POST | /v1/games/{gameId}/island-skips | 사용자 무인도 대기 |
| POST | /v1/games/{gameId}/ai/island-skips | AI 무인도 대기 |
| POST | /v1/games/{gameId}/quiz-answers | 사용자 선택지 서버 판정 |
| POST | /v1/games/{gameId}/quiz-timeouts | 서버 기한 경과 확인·시간 초과 판정 |
| POST | /v1/games/{gameId}/ai/quiz-answers | AI 선택·판정 |
| POST | /v1/games/{gameId}/ai/rolls | AI 현재 차례의 서버 주사위·이동·퀴즈 발급 |
| POST | /v1/games/{gameId}/rolls | 사용자 이동·치즈 능력·출발 보상·필요한 퀴즈 발급 |

상태 변경 요청은 UUID Idempotency-Key 헤더와 application/json 본문이 필요합니다.
create 본문은 mode: AI와 characterId, initiative/rolls는 expectedVersion만 받습니다.
사용자 ID·주사위 숫자·문항 정답을 클라이언트 입력으로 받지 않습니다.

## 인증과 원자적 처리

플랫폼 verify_jwt 기본값 true를 유지합니다.
handler는 Authorization의 사용자 JWT를 /auth/v1/user에서 추가 검증합니다.
Auth가 확인한 user.id만 서버 전용 public.finpop_game_action으로 전달합니다.
anon·authenticated는 이 RPC를 직접 실행할 수 없습니다.
service_role 전용 자격 증명은 서버 환경변수에서만 읽고 로그·응답·Android에 기록하지 않습니다.

한 RPC 호출 안에서 사용자별 트랜잭션 잠금 → 요청 키 조회 → 게임 행 잠금 →
입력·상태 버전 검증 → 게임 변경 → 최초 응답 기록을 처리합니다.
검증 실패 시 게임 변경은 롤백하고 알려진 오류 응답만 요청 기록에 보존합니다.
같은 키·같은 요청은 상태 버전이 달라져도 최초 응답을 그대로 반환합니다.
같은 키·다른 요청은 IDEMPOTENCY_CONFLICT입니다.
수정된 본문이나 새 동작은 새 요청 키를 사용해야 합니다.

서버 환경변수:
SUPABASE_URL, SUPABASE_ANON_KEY, SUPABASE_SERVICE_ROLE_KEY.
이 어댑터는 기본 legacy service_role JWT 환경변수를 사용합니다.
sb_secret 형태로 전환할 때는 서비스 인증 헤더 방식을 별도로 변경·검증해야 합니다.

## 규칙 구현과 현재 제한

출전 캐릭터 보유·해금 레벨·강화 레벨을 DB에서 검증합니다.
학습 단계는 1 + floor(completed_game_count / 2)로 생성 시 고정합니다.
AI는 CHEESE Lv.1로 시작합니다. 계정 wallet_gold는 게임 생성 시 차감하지 않습니다.
치즈 이동 능력은 Lv.1~5에 30/35/40/45/50G이며, 출발 통과·도착은 별도 200G입니다.
이동할 때 남은 턴은 차감하지 않습니다. 퀴즈 판정 API를 추가했습니다. 구매·패스·차례 종료·무인도 대기 API를 로컬 구현했습니다. 실제 프로젝트에는 최신 업데이트 SQL과 Edge Function 적용이 필요합니다.
다른 캐릭터 이동 능력은 ABILITY_UNAVAILABLE로 거절하고 이동·재화를 저장하지 않습니다.
황금열쇠는 EVENT_RULES_UNAVAILABLE로 거절하고 상태를 저장하지 않습니다.
무인도는 도착 상태와 다음 차례 대기 1회를 기록하지만 대기 처리 API는 아직 없습니다.
AI 선공이면 사용자 rolls는 거절됩니다. AI 이동은 별도 ai/rolls 경로로 처리합니다. AI 퀴즈 판정을 추가했으며 구매·차례 종료는 아직 지원하지 않습니다.
따라서 이 서버 구현만으로 전체 게임을 완주할 수는 없습니다.

## 퀴즈 저장소

문항 원본·버전·정답·출처를 분리한 quiz-db-draft.sql 구조를 사용합니다. 상세 등록 방법은 quiz-db-design.md를 참고합니다.
승인된 버전만 출제하고 원본 question_id 기준으로 게임 내 중복을 차단합니다.
사용자가 검수한 기본 금융 문항 30개를 1단계·10개 주제별 3개씩 APPROVED로 등록했습니다. 원본은 supabase/seed-data/basic-finance-30.json, 등록 SQL은 docs/api/basic-finance-30-import.sql입니다. 정답이 포함된 원본 JSON은 서버 자료이며 Android assets나 res에 넣지 않습니다. 승인된 해당 주제·단계 문항이 없으면 QUIZ_POOL_EXHAUSTED입니다.
발급한 문제·보기는 공개 이력에, 정답·해설은 비공개 이력에 같은 트랜잭션으로 고정합니다.
응답에는 정답·해설을 넣지 않습니다. 같은 게임에서 이미 쓴 원본은 버전이 달라도 다시 내지 않습니다.
현재 선택은 ID 순서이며 뉴스 혼합·오답 복습 우선순위는 후속 콘텐츠 기능입니다.
문항 검증은 보기 ID 1~4·텍스트·단일 정답을 포함하며 실제 콘텐츠 검수도 별도로 필요합니다.

## 검증과 한계

문항 구조 통합 검증 결과는 game-db-validation.md를 참고합니다. HTTP 어댑터 테스트도 함께 실행합니다.
DB 테스트는 기존 스키마를 재현하고 service_role 호출을 수행했습니다.
HTTP 테스트는 Auth·PostgREST의 네트워크 응답을 모사했습니다.
실제 사용자 JWT로 배포된 함수와 Data API를 거쳐 게임 생성·선공 결정이 성공했습니다. 실제 주사위 이동·다중 연결 동시성은 아직 검증하지 않았습니다.
Android debug 화면과 INTERNET 권한을 추가했습니다. 기존 로그인 화면과 팀원 보드 화면에는 연결하지 않았습니다.

재실행:
cd tools/db-schema
npm install
npm test

## 실제 프로젝트 적용·확인 결과

- 기존 users·characters·user_characters는 유지하고 게임·문항 테이블 12개를 추가했습니다.
- 새 테이블 12개의 RLS 활성화와 RPC 실행 권한(anon·authenticated 불가, service_role 가능)을 확인했습니다.
- 기본 금융 문항 30개가 10개 주제별 APPROVED 3개로 조회됐습니다.
- finpop-api 배포 성공과 서버 코드 실행을 확인했습니다.
- 실제 게임 생성 후 INITIATIVE_PENDING과 게임 ID 발급을 확인했습니다.
- 선공 결정 HTTP 200 이후 TURN_READY·현재 차례 P2(AI)를 확인했습니다.
- 함수 이름 접두사가 남는 배포 경로를 처리하도록 handler.mjs를 수정했습니다. 전체 경로·함수 접두사 경로·직접 경로를 포함한 HTTP 테스트 18개가 통과했습니다.
- AI 선공 상태에서 사용자 주사위 버튼이 비활성화됐습니다. AI 차례 API가 없어 실제 이동 검증은 진행하지 못했습니다.

## 앱 실행과 남은 작업

일반 app 실행 → 시작 화면 → Google 로그인 → 팀원 보드 순서로 확인합니다.
사용자 토큰을 별도 입력하지 않으며 실제 로그인 세션으로 서버 문제·답변·판정·구매·차례 종료를 호출합니다.
실패한 변경 요청은 원래 본문과 요청 키로 재시도합니다.
황금열쇠 효과·최종 승패·계정 보상과 실제 서버 예외 시나리오 검증은 후속 작업입니다.

공식 참고:
- https://supabase.com/docs/guides/functions/auth-headers
- https://supabase.com/docs/guides/database/functions
- https://supabase.com/docs/guides/functions/secrets



## AI 이동 업데이트 (로컬 구현 완료, 원격 적용 전)

사용자·AI 이동은 같은 RPC 로직을 사용하며 ai-roll 연산은 P2 차례에만 허용합니다.
요청 본문은 expectedVersion만 받고, 인증된 게임 소유자와 요청 키·상태 버전을 검사합니다.
응답에는 playerId, diceValue, movementPath, events, game이 포함됩니다.
퀴즈는 이동한 참가자에게 발급되며 정답·해설은 이동 응답에 포함하지 않습니다.

기존 프로젝트 적용 순서:
1. SQL Editor에서 docs/api/game-ai-movement-update.sql 전체를 실행합니다.
2. Edge Functions → finpop-api → handler.mjs를 로컬 최신 파일 전체로 교체하고 Deploy updates를 누릅니다.
3. Android debug 앱을 다시 빌드·실행하고 기존 게임 ID를 조회합니다.
4. 서버 상태로 보드 열기를 누르면 TURN_READY·P2에서 게임·버전별 한 번 자동 이동을 요청합니다.
5. 응답 유실 시 같은 요청 재시도, 확정된 HTTP 오류 해결 후에는 AI 이동 요청 다시 시도를 사용합니다.

업데이트 SQL은 기존 계정·문항·게임을 변경하지 않고 public.finpop_game_action만 교체하며 서버 전용 권한을 유지합니다.
이미 적용한 프로젝트에서 game-quiz-setup.sql이나 문항 등록 SQL을 다시 실행하지 않습니다.
새 프로젝트용 game-quiz-setup.sql에도 같은 AI 이동 함수를 반영했습니다.
팀원 보드 원본은 수정하지 않고 debug 연결 파일에서 위치와 주사위 색상을 변환합니다.
황금열쇠 도착은 기존과 같이 EVENT_RULES_UNAVAILABLE로 전체 이동을 롤백합니다.
AI가 퀴즈에 도착하면 QUIZ_OPEN, 퀴즈가 필요 없는 칸이면 TURN_REVIEW에서 멈춥니다.
이번 단계는 AI 이동까지이며 퀴즈 판정·정산·차례 종료·자동 반복 플레이는 후속 작업입니다.

## 퀴즈 판정 업데이트 (로컬 검증 완료, 원격 적용 전)

- 사용자 답변: POST /v1/games/{gameId}/quiz-answers
  - 본문: expectedVersion, attemptId, choiceId(1~4).
- 시간 초과 확인: POST /v1/games/{gameId}/quiz-timeouts
  - 본문: expectedVersion, attemptId. 마감 전에는 QUIZ_NOT_EXPIRED입니다.
- AI 답변: POST /v1/games/{gameId}/ai/quiz-answers
  - 본문: expectedVersion, attemptId. AI 선택지는 서버에서만 70% 정답 확률로 결정합니다.
- 모든 변경 요청은 사용자 JWT와 UUID Idempotency-Key가 필요합니다.

서버 시각이 deadline_at 이상이면 사용자 선택이 정답이어도 TIMEOUT이며 선택지는 저장하지 않습니다.
AI도 이미 기한이 지났으면 확률 추첨 없이 TIMEOUT입니다. 기기 표시 시간은 안내용입니다.
미소유 칸 정답은 PURCHASE_PENDING, 오답·시간 초과는 TURN_REVIEW로 전환합니다.
상대 소유 칸은 정답이면 가격 절반, 오답·시간 초과면 전액을 같은 트랜잭션에서 정산합니다.
출금·입금과 판정을 함께 저장하며 음수 잔액을 허용합니다.
이 판정 API는 턴을 차감하거나 다음 참가자로 바꾸지 않습니다.
구매·패스와 차례 종료는 후속 작업입니다.

판정 응답은 data.result와 data.game입니다. 판정 후에만 정답·해설·출처를 공개합니다.
스냅샷에는 현재 차례의 lastQuizResult가 포함되므로 상태 재조회로 판정 결과를 복원할 수 있습니다.
즉시 응답의 result.payment에는 방문 정산 내역이 포함되며 재조회 시에는 저장된 양쪽 잔액을 확인합니다.
팀원 보드는 수정하지 않고 debug GameServerQuizControls에서 답변·남은 시간·결과를 연결합니다.
AI 퀴즈는 보드에서 한 번 자동 판정하고, 사용자 퀴즈는 선택지 제출 또는 시간 초과를 요청합니다.
요청 유실 시 같은 요청 키로 재시도합니다.

기존 프로젝트 적용:
1. docs/api/game-quiz-resolution-update.sql 전체를 SQL Editor에서 실행합니다.
2. finpop-api의 handler.mjs를 로컬 최신 파일로 교체하고 Deploy updates를 누릅니다.
3. Android debug 앱을 재빌드하고 기존 게임 상태를 조회해 보드를 엽니다.
4. 기존 AI 퀴즈가 오래됐다면 TIMEOUT·TURN_REVIEW가 정상입니다.
5. 새 사용자 퀴즈의 선택지와 새 AI 퀴즈 확률 판정도 별도 검증합니다.

업데이트는 함수만 추가·교체하고 기존 테이블과 데이터를 유지합니다.
game-quiz-resolution-functions.sql은 서버 전용 보조 함수의 원본이며 개별 적용 대신 update.sql을 사용합니다.
신규 설치용 game-quiz-setup.sql에도 동일한 함수를 반영했습니다.
기존 프로젝트에서 초기 설정·문항 등록 SQL을 다시 실행하지 않습니다.

## 구매·차례 종료 업데이트 (로컬 검증 완료, 원격 적용 전)

현재 배포용 최신 파일은 docs/api/game-turn-update.sql입니다.
기존 계정·문항·게임을 유지하며 보조 함수·스냅샷·RPC를 일괄 교체합니다.
이전 AI 이동·퀴즈 업데이트를 포함하므로 적용 후 과거 업데이트 SQL로 되돌리지 않습니다.

- purchases 본문: expectedVersion, tileId, action(BUY 또는 PASS).
- ai/purchases 본문: expectedVersion, tileId. 잔액이 가격 이상이면 BUY, 부족하면 PASS를 서버에서 선택합니다.
- turn-completions와 ai/turn-completions 본문: expectedVersion, turnId.
- island-skips와 ai/island-skips 본문: expectedVersion, turnId.
- 모든 변경 요청에 사용자 JWT와 UUID Idempotency-Key가 필요합니다.

구매는 현재 차례의 정답 문항·구매 대기 칸·소유자·가격·잔액을 검증하고 출금과 소유권을 원자적으로 저장합니다.
클라이언트가 가격·AI의 구매 선택을 입력할 수 없습니다.
잔액 부족 구매는 INSUFFICIENT_GOLD이며 구매 대기를 유지해 패스할 수 있습니다.
구매·패스 후 TURN_REVIEW이며 아직 턴을 차감하지 않습니다.
구매 응답 data.purchaseEvent는 eventId, playerId, tileId, action, amount를 포함합니다.

차례 종료는 TURN_REVIEW이며 미판정 퀴즈·구매·이벤트가 없을 때만 가능합니다.
현재 turnId를 확인하고 차례를 닫으며 남은 턴을 정확히 한 번 차감합니다.
상대에게 남은 턴이 있으면 상대, 없으면 현재 참가자가 계속 진행합니다.
양쪽 턴이 모두 소진되면 FINISHED로 저장하고 현재 참가자·차례를 비웁니다.
계정 wallet_gold·total_points·completed_game_count와 최종 보상은 변경하지 않습니다.
무인도 대기는 TURN_READY·대기 1회·미이동 상태에서만 허용하며 주사위·보상 없이 1턴을 소비하고 대기를 해제합니다.

Android debug 보드는 사용자 구매·패스·차례 종료·무인도 대기 버튼을 제공합니다.
AI는 이동→퀴즈 판정→구매·패스→차례 종료를 상태별로 자동 요청합니다.
오류 발생 시 자동 무한 재시도를 하지 않으며 응답 유실은 원래 요청 키로 재시도합니다.
구매 응답의 eventId로 소유 도장 애니메이션 중복을 막고 칸 ID를 화면 기준으로 변환합니다.
같은 화면 세션에서는 AI 차례가 끝나도 마지막 퀴즈 결과를 확인할 수 있습니다.
팀 보드의 디자인은 유지하며 게임 종료 문구만 양쪽 턴 소진을 검사하도록 보완했습니다.

기존 프로젝트 적용:
1. SQL Editor에서 game-turn-update.sql 전체를 실행합니다.
2. finpop-api의 handler.mjs를 로컬 최신 내용으로 교체하고 Deploy updates를 누릅니다.
3. 앱을 재빌드하고 기존 TURN_REVIEW·P2 게임을 조회해 보드를 엽니다.
4. AI 남은 턴이 7→6으로 바뀌고 현재 차례 P1·TURN_READY인지 확인합니다.
5. 사용자 이동·퀴즈·구매 또는 패스·차례 종료 후 AI가 진행하고 P1으로 복귀하는지 확인합니다.

황금열쇠·나머지 캐릭터 능력·최종 계정 보상과 실제 동시성 검증은 별도 작업입니다.
