# FinPOP 🍿

보드게임형 금융 지식 습득 서비스 — 모바일소프트웨어 기말 프로젝트 (팀 **티끌모아태산**)

## 팀

| 이름 | 주 담당 |
| --- | --- |
| 남윤아 | 인증·DB·이어하기, NAVER·Gemini·예약 생성·보관함 |
| 정수민 | Compose 화면, 보드·주사위·캐릭터·탭·퀴즈 UI |
| 홍예빈 | 게임 규칙·AI·이벤트·서버 게임 처리·결과 |

## 기술 스택
Kotlin, Jetpack Compose, Supabase(Auth·PostgreSQL·Edge Functions), Room, DataStore, NAVER 뉴스 API, Gemini

## 디렉토리 구조

    FinPOP/
    ├── app/src/main/java/com/finpop/app/
    │   ├── core/        # network, database, datastore, designsystem, model
    │   ├── data/        # Repository (auth, user, character, game, quiz, news, market)
    │   ├── feature/     # auth, home, game, quiz, news, archive, character, mypage
    │   └── navigation/  # NavHost, 하단 탭
    ├── supabase/        # functions/finpop-api, migrations
    └── docs/            # api, design, meeting-notes

## 협업 규칙
- 브랜치: `main` ← `develop` ← `feat/<기능>`
- 커밋: `feat:`, `fix:`, `docs:`, `refactor:`, `chore:`
- `develop`으로 PR, 1명 이상 리뷰 후 머지