# 29EBWO

구글 문서를 **한 문장씩 한 페이지에** 보여 주는 e-ink 뷰어.
**Boox Poke4 Lite 한 대만을 위한** 개인용 앱이다.

```
Android 11 (API 30)  ·  arm64-v8a  ·  Kotlin + View/Canvas
화면 758 × 1024 px @ 212dpi  =  572 × 773 dp
```

## 왜 이렇게 만들었나

**Compose 를 쓰지 않았다.** 회색을 하프톤 점무늬로 내려면 무늬를 화면 픽셀에
1:1 로 얹어야 하고, e-ink 에서는 어디를 언제 고쳐 그리는지를 직접 쥐고 있어야
한다. 리컴포지션과 기본 애니메이션이 그 통제를 흐린다. 메모리가 1.8GB 뿐인
기기이기도 하다.

**구글 API 클라이언트 라이브러리를 쓰지 않았다.** 우리가 부르는 것은 목록과
본문 두 갈래뿐이라 OkHttp 로 REST 를 곧장 부른다.

**본문은 `files.export` 로 `text/plain` 을 받는다.** 그래서 인코딩을 알아맞힐
일이 없다 — 언제나 UTF-8 이다. (SAF 문서 선택창은 구글 문서를 PDF 로만 내주지만
그 제약은 SAF 것이고 API 에는 없다.)

## 화면

**목록** — 스크롤이 없다. 화면 높이에 들어갈 만큼만 놓고 이전/다음으로 넘긴다.
받아 둔 문서는 크기가 뜨고 `지움` 으로 로컬 사본만 지운다.

**리더** — 한 쪽에 문장 하나. `"가자."` 도 `헉` 도 한 쪽을 다 쓴다.

- **아래 절반**을 누르면 다음 문장
- **위 절반**을 누르면 조작판 — 5초 동안 조작이 없으면 스스로 숨는다
- 조작판이 숨을 때 **시스템 막대도 함께** 숨어 글만 남는다
- 타임라인의 칸을 누르면 그 자리로 간다 (어림자리)

쓸어 넘기기와 스크롤은 두지 않았다. e-ink 에서 손가락을 따라 잇달아 고쳐
그리면 잔상만 남는다.

## 글이 놓이는 자리

글은 **화면 가운데 60% 상자** 안에만 놓이고 가로·세로 모두 가운데로 맞춘다.
18dp 기준으로 **한 줄 16자, 한 쪽 218자**가 들어간다(실측). 상자에 들어가지
않는 문장만 쪼갠다 — 글자 크기를 줄이지는 않는다.

`Ink.BOX_FRACTION` 과 `Ink.TEXT_DP` 가 그 두 값이다.

## 문장 나누기

`Sentences` 가 문장으로 가르고, `PageBuilder` 가 들어가지 않는 문장만 쪼갠다.
쪼갤 때는 쉼표·닫는 따옴표처럼 뜻이 끊기는 자리를 먼저 쓴다.

자르지 않는 자리들 — 숫자 사이의 점(`3.14`), 날짜를 맺는 점
(`2026. 9. 12. 에`), 줄머리의 번호(`1. 첫째`), 줄임말(`Dr.`), 이름
머리글자(`J. R. R.`), 확장자(`ebwo.kt`). 줄임표는 한 덩이로 본다.

**줄바꿈은 `WordWrap` 이 띄어쓰기에서만 한다.** 안드로이드에 맡기면 한글이
음절 단위로 아무 데서나 끊기고, 그걸 막는 `LineBreakConfig` 는 API 33 부터라
이 기기에서는 쓸 수 없다.

이 세 갈래는 안드로이드에 기대지 않는다. Android Studio 에 든 `kotlinc` 로
JVM 에서 그대로 시험한다.

```
kotlinc -include-runtime -d t.jar app/src/main/java/com/artbrain/ebwo/text/*.kt Test.kt
```

## 회색은 하프톤으로

`Halftone` 이 4×4 베이어 무늬로 중간 밝기를 낸다. **무늬는 dp 가 아니라 화면
픽셀로 짠다** — dp 로 잡으면 212dpi 에서 1.325 배로 늘어나며 보간이 끼고 흐린
회색으로 뭉개진다. `isFilterBitmap` 과 앤티에일리어싱을 모두 끈다.

글자는 반대로 앤티에일리어싱을 켠다. 212dpi 에서 계단이 보이면 읽기 힘들다.

## 글꼴

본문은 **JTBC 명조(`JTBC-Regular.ttf`)** 로 그린다. 재배포하지 않으려고
저장소에는 넣지 않았다. [JTBC 글꼴 배포처](https://www.jtbc.co.kr/fonts)에서
받아 아래 자리에 두면 된다.

```
app/src/main/res/font/jtbc_regular.ttf
```

없어도 빌드는 되지만 기기 기본 글꼴로 그려진다
(`App.bodyFont` 가 `Typeface.DEFAULT` 로 물러난다).

## 만들기

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm enable com.artbrain.ebwo      # ← 빠뜨리면 안 된다
```

**Boox 는 새로 설치한 앱을 자동으로 동결한다**(`enabled=3`). `pm enable` 을
빠뜨리면 `Activity class does not exist` 로 보여 매니페스트 문제로 오해한다.

그리고 기기에서 **Apps → ❄ → `Automatically enable freezing after installing
an app` 을 꺼야 한다.** 켜져 있으면 구글 승낙 화면이 앞에 뜨는 사이 앱이
백그라운드로 내려가면서 동결·종료되고, 승낙 결과를 받을 앱이 사라진다.
(그래도 견디도록 하려던 일은 `savedInstanceState` 에 남긴다.)

## 구글 설정

프로젝트 `ebwo-508413`, 스코프 `drive.readonly` 하나.

1. Drive API 사용 설정
2. OAuth 동의 화면 — 외부 / 테스트, 테스트 사용자에 본인 계정
3. **OAuth 클라이언트 · Android 유형** — 패키지 `com.artbrain.ebwo` + 디버그 SHA-1

3번이 빠지면 `UNREGISTERED_ON_API_CONSOLE` 이 난다. 만든 뒤 **5분쯤 기다려야**
반영된다 — 그 전에 시도하면 동의 페이지가 `400 malformed` 로 떨어지는데,
스코프 문제로 오해하기 쉽다.

열쇠는 기기에 이미 든 구글 계정으로 받는다(`AuthorizationClient`). 다른 앱의
열쇠를 빌리는 것이 아니라 우리 앱 이름으로 새로 받는 것이라, 웹 로그인 칸 없이
계정 고르는 창만 뜬다. 승낙은 처음 한 번뿐이고 이후로는 조용히 갱신된다.

**테스트 상태의 토큰이 7일마다 만료될 수 있다.** 이 경로는 refresh token 을
앱이 들지 않고 GMS 가 쥐므로 안 걸릴 수도 있다 — 일주일 써 봐야 안다.
걸리면 서비스 계정(폴더 공유) 으로 바꾸면 영구히 해결된다.

## 스크린샷 찍기

Onyx 의 `screencap -p` 는 PNG 앞에 `capture from screenshot!` 을 찍는다.
PNG 머리(`\x89PNG`)부터 잘라내야 유효한 이미지가 된다.
