# 대결 일시정지·재개·포기

## 적용 범위

팀원 보드 디자인을 유지하고 대결 메뉴에 세 동작을 연결합니다. 로그인·로그아웃 정책은 변경하지 않습니다. 앱을 백그라운드로 보내거나 강제 종료하는 동작은 자동 일시정지가 아닙니다.

| 동작 | 경로 (POST) | 본문 |
| --- | --- | --- |
| 일시정지 | `/v1/games/{gameId}/pause` | `{"expectedVersion": 4}` |
| 재개 | `/v1/games/{gameId}/resume` | `{"expectedVersion": 5}` |
| 포기 | `/v1/games/{gameId}/abandon` | `{"expectedVersion": 6}` |

Bearer 사용자 세션과 UUID `Idempotency-Key`가 필요합니다. 동일 요청 재시도에는 같은 키·본문을 사용합니다. 게임 소유자·버전은 서버에서 확인하며 잔액·시간·보상 값은 앱에서 받지 않습니다.

성공 응답은 기존 앱의 원격 상태 처리와 호환되도록 `data`에 전체 게임 스냅샷을 반환합니다. `meta`와 `error` 형식은 유지합니다. Notion의 pause·abandon 요약 응답 필드에 전체 상태가 추가되는 형태이며, 문서 내용은 자동 변경하지 않았습니다.

## 서버 규칙

- `ACTIVE → PAUSED`: phase·현재 차례·잔액·턴·소유권을 보존하고 버전을 올립니다. 남은 퀴즈 시간을 서버에서 밀리초 단위로 저장합니다.
- 이미 마감된 퀴즈는 기존 시간 초과 판정·정산을 먼저 확정한 뒤 일시정지합니다. 일시정지로 답변 시간을 되살리지 않습니다.
- `PAUSED → ACTIVE`: 같은 문항·보기·차례를 유지하고 서버 재개 시각에 저장된 남은 시간을 더해 마감 시각을 설정합니다.
- 일시정지 중에는 기존 이동·답변·구매·차례 종료 요청이 `GAME_NOT_ACTIVE`로 거절됩니다. 일시정지한 대결도 활성 슬롯을 사용합니다.
- `ACTIVE/PAUSED → ABANDONED`: 열린 차례와 대결 진행을 닫고 활성 슬롯을 해제합니다. 계정 지갑·포인트·완료 횟수는 변경하지 않습니다. 퀴즈 기록은 삭제하지 않고 종료된 대결의 미결 문항은 스냅샷에 노출하지 않습니다.
- 포기한 대결은 재개할 수 없습니다. 새 대결은 사용자가 명시적으로 시작합니다.
- 예약 시간 초과 작업을 추후 추가한다면 `game_sessions.status='ACTIVE'`인 문항만 처리해야 합니다.

스냅샷 추가 필드: `pausedAt`, `pausedPhase`, `remainingQuizMs`, `abandonedAt`, `rewardGranted`, `completedCountIncremented`. 현재 보상 시스템은 미구현이므로 뒤 두 필드는 false입니다.

## 앱 확인

1. 대결 메뉴에서 **대결 일시정지**를 선택합니다. 퀴즈가 펼쳐져 있으면 뒤로가기로 접은 뒤 보드 메뉴를 엽니다.
2. 일시정지 안내에서 **대결 재개**로 이어가거나 로그인 화면으로 나갑니다. 다시 게임을 조회하면 PAUSED 상태를 유지합니다.
3. **대결 포기**는 확인 팝업에서 확정한 뒤 서버에 요청합니다.
4. 응답이 유실되면 다른 변경 요청을 차단하고 같은 요청으로 재시도합니다.

## 원격 적용 순서

로컬 코드 수정만으로 배포된 서버는 바뀌지 않습니다.

1. 기존 `game-turn-update.sql`이 적용된 DB에서 `docs/api/game-session-controls-update.sql` 전체를 실행합니다. 계정·캐릭터·문항 테이블을 재생성하지 않습니다.
2. `supabase/functions/finpop-api/handler.mjs` 변경을 포함해 Edge Function을 배포합니다. 진입점 `index.ts`는 그대로 사용합니다.
3. Android app을 다시 빌드·설치합니다.
4. 실제 로그인 세션으로 퀴즈 중 일시정지 → 시간 경과 → 재개 → 답변, AI 차례 중 정지·재개, 포기 후 새 게임 생성, 중복 요청을 검증합니다.

업데이트 SQL은 트랜잭션으로 실행하며 재적용해도 기존 게임을 초기화하지 않습니다. anon·authenticated의 RPC 직접 실행 권한을 차단하고 service_role 권한을 유지합니다. 과거 RPC 업데이트 SQL을 다시 실행하면 새 기능이 덮어써질 수 있으므로 이 업데이트를 마지막에 적용합니다.

## 로컬 검증

- Android: `gradlew.bat :app:assembleDebug :app:testDebugUnitTest --offline`
- DB: `tools/db-schema`에서 의존성 설치 후 `node validate-rpc.mjs`
- HTTP: `node --test tools/db-schema/handler.test.mjs`

배포된 서버와 에뮬레이터의 실제 플레이 검증은 별도로 수행합니다.
