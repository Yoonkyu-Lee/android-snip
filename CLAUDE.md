# Project: AndroidSnip

## Objective

Windows의 Win + Shift + S에 대응하는 가볍고 독립적인 Android 부분 화면 캡처 애플리케이션을 개발한다.

최종 목표: 삼성 갤럭시탭에서 **Good Lock One Hand Operation+(OHO+) 핸들 제스처**로 캡처를 시작하고, 화면의 특정 영역을 S펜 또는 손가락으로 드래그하면 해당 이미지가 즉시 클립보드에 복사되어 ClipCascade를 통해 Windows PC에 자동 동기화되고, PC에서 Ctrl+V로 붙여넣을 수 있게 한다. (기존에 OHO+ 핸들에 할당해 쓰던 Smart Select(사각형)를 이 앱으로 교체하는 것이 목적이다.)

## Environment (조사 완료: 2026-11-21)

- Host: Windows 11 Pro. Shell: PowerShell / Git Bash
- Device: Samsung Galaxy Tab S7+ Wi-Fi (SM-T970, gts7xlwifi), serial R54N900MJRW, USB 연결
- Android 13 (API 33), One UI 5.1.1
- Display: 1752x2800 px, density 340
- Device에는 user 150(보안 폴더 등)이 있으므로 `pm list packages` 같은 명령은 `--user 0`을 붙일 것
- ADB: WinGet Google.PlatformTools (PATH에 있음)
- JDK: Microsoft OpenJDK 21 (`C:\Program Files\Microsoft\jdk-21.0.13.102-hotspot`)
- Android SDK: `%LOCALAPPDATA%\Android\Sdk` (cmdline-tools/latest로 직접 설치)
- ClipCascade Android 패키지: `com.clipcascade` (MainActivity가 `SEND` `image/*`, `*/*`를 받음)
- ClipCascade 서버: 별도 상시 가동 미니 PC, Tailscale 경유. Windows/Android 클라이언트 설정 완료
- 기존 접근성 서비스(건드리지 말 것): com.os.touchrejection, io.github.sds100.keymapper, com.llamalab.automate
  - AndroidSnip 접근성 서비스 활성화는 사용자가 기기에서 직접 한다 (ADB로 secure settings 변경 금지)
  - **주의: `am force-stop` / `am start -S` 로 com.androidsnip 를 강제 종료하면 Android가 접근성 서비스를 비활성화한다.** 테스트에서 절대 사용하지 말 것.
    액티비티 재시작은 `am start -f 0x10008000` (NEW_TASK|CLEAR_TASK) 사용

## Known Problem

기존 삼성 Smart Select의 이미지 복사 기능이 ClipCascade와 호환되지 않는다.

- 텍스트 양방향 자동 동기화: 성공
- 브라우저에서 이미지 복사 후 PC 자동 동기화: 성공
- Smart Select에서 공유 메뉴로 ClipCascade에 전송: 성공
- Smart Select에서 복사 버튼을 통한 자동 동기화: 실패
- Smart Select 이미지를 삼성 노트에 붙여넣기: 성공
- KDE Connect 강제 종료 후 재시험: 동일하게 실패
- ClipCascade의 복사 이벤트 감지용 FloatingActivity는 정상 실행됨

따라서 ClipCascade를 수정하지 않고, 호환되는 부분 캡처 앱을 독립적으로 개발한다.

## Functional Requirements

1. **OHO+ 핸들 제스처 진입점.** OHO+의 "앱 실행" 또는 "앱 바로가기" 목록에 나타나는 전용 캡처 Activity(또는 App Shortcut)를 제공한다. 실행하면 UI 없이 바로 캡처/영역 선택이 시작되어야 하며, 진입점 자체가 캡처 이미지에 찍히면 안 된다. **플로팅 버튼은 구현하지 않는다.**
2. Android Quick Settings Tile을 통한 캡처 시작 (예비 진입점).
3. 화면 전체에 대한 사각형 영역 선택 인터페이스.
4. 손가락과 S펜 입력 지원.
5. 드래그를 끝내는 순간 선택 영역을 자동으로 이미지 클립보드에 복사.
6. 별도의 완료/복사 버튼이 필요 없는 Auto Copy 모드 (기본값).
7. 선택 영역을 수정할 수 있는 선택적 Adjust 모드.
8. 가로 및 세로 화면 지원.
9. 캡처 이미지에 앱 자신의 오버레이, 진입점, 선택 영역 테두리가 포함되지 않아야 함.
10. 임시 파일 자동 정리.
11. 불필요한 OCR, AI, 이미지 편집, 네트워크 기능을 포함하지 말 것.

## Technical Requirements

- Native Android / Kotlin, Gradle 기반 빌드
- 가능한 한 Android 표준 API 사용
- AccessibilityService.takeScreenshot() 우선 검토
- 선택 오버레이는 TYPE_ACCESSIBILITY_OVERLAY 사용 (SYSTEM_ALERT_WINDOW 권한 불필요)
- 진입점 Activity는 화면을 그리지 않고(NoDisplay/Translucent) 서비스에 요청 후 즉시 종료. 서비스는 캡처를 먼저 끝낸 뒤 오버레이를 띄운다
- Bitmap 기반 영역 자르기, PNG 이미지
- FileProvider / ContentProvider
- ClipboardManager 및 ClipData.newUri()
- 올바른 image MIME type과 URI 읽기 권한
- 최소 권한 원칙, 인터넷 권한을 요구하지 않는 구조, 루팅 불필요
- 화면 캡처는 명시적인 사용자 동작에 의해서만 실행
- Android의 보안 화면(FLAG_SECURE) 캡처 제한을 존중할 것

## Development Phases

### Phase 1: Compatibility Proof of Concept

전체 캡처 앱을 구현하기 전에, 테스트용 PNG 이미지를 Android 표준 클립보드에 등록하는 최소 앱을 만든다.

- ADB로 실제 갤럭시탭에 설치하고 실행한다.
- Windows 클립보드를 PowerShell STA 또는 적절한 Windows Clipboard API로 직접 검사하여 ClipCascade가 실제 이미지 데이터를 전달하는지 검증한다.
- 이전 클립보드 이미지가 남아 성공으로 오인하지 않도록, 테스트마다 고유한 이미지를 생성하고 픽셀 데이터 또는 정규화된 이미지 해시를 비교한다.
- 실패하면 MIME type, ContentProvider, URI 읽기 권한, 클립보드 접근 타이밍, ClipCascade의 공개 소스 코드를 조사한다.
- 표준 클립보드 방식이 불가능하다고 확인되면, 사용자 추가 조작 없이 ClipCascade의 기존 이미지 공유 인터페이스(SEND image/*)를 호출하는 방식을 검토한다.
- 호환성이 검증되기 전에는 복잡한 UI를 구현하지 않는다.

### Phase 2: Screen Capture

화면 캡처, 사각형 영역 선택, 이미지 자르기 및 자동 복사를 구현한다. 좌표계, DPI, 화면 회전, 시스템 바 영역, 오버레이 포함 문제를 정확하게 처리한다. 진입점 Activity가 실행될 때 하위 앱의 일시적 UI(드롭다운, 툴팁 등)가 닫히는지 확인하고 기록한다.

### Phase 3: UX

OHO+ 진입점(전용 캡처 Activity와 App Shortcut)과 Quick Settings Tile을 구현한다. Auto Copy를 기본 모드로, Adjust 모드를 옵션으로 제공한다. OHO+ 핸들에 기능을 할당하는 것은 사용자가 직접 하며, ADB 테스트에서는 같은 Intent를 `am start`로 실행하여 검증한다.

### Phase 4: Autonomous Testing

ADB를 통해 다음을 직접 반복 수행한다.

- APK 빌드 및 설치, 앱 실행
- UI 구조 조사 (uiautomator dump)
- 터치 및 드래그 시뮬레이션
- 스크린샷 획득 및 분석
- Logcat 오류 분석
- Windows 클립보드 결과 검증
- 코드 수정 및 재빌드
- 화면 회전 및 해상도 테스트

테스트가 실패하면 원인을 조사하고 수정한 뒤 다시 테스트한다. APK가 빌드되었다는 이유만으로 완료하지 않는다.

### Phase 5: Final Delivery

정상 작동이 확인된 APK, 전체 소스 코드, 빌드 명령어, 간결한 README를 제공한다.

## Autonomous Execution Rules

- 계획만 작성하고 작업을 중단하지 않는다.
- 필요한 Android SDK, JDK, Gradle 및 개발 도구는 직접 조사하고 구성한다.
- Android Studio GUI를 사용자가 직접 조작하도록 요구하지 않는다.
- ADB로 할 수 있는 작업을 사용자에게 대신 요청하지 않는다.
- 사용자 개입이 꼭 필요한 시스템 권한 승인(예: 접근성 서비스 활성화)과 OHO+ 핸들 할당만 요청한다.
- 기존 ClipCascade 서버, Tailscale, Good Lock/OHO+ 및 기타 애플리케이션의 설정을 변경하지 않는다.
- 기존 접근성 서비스 설정과 개인 데이터를 훼손하지 않는다.
- 불필요한 의존성과 과도한 추상화를 피한다. 단순하고 평탄하며 유지보수가 쉬운 코드 구조를 지향한다.
- 실제 기기에서 End-to-End 테스트가 성공하기 전까지 완료를 선언하지 않는다.
- 검증하지 못한 사항은 명확하게 기록한다.

## Progress

- Phase 1 (2026-11-21): **성공.** `ClipData.newUri()` + 자체 ContentProvider(`content://com.androidsnip.shots/<file>`, getType=image/png, grantUriPermissions)로
  설정한 PNG가 ClipCascade를 거쳐 Windows 클립보드에 픽셀 단위로 일치하여 도착 (5/5회, 고유 nonce 이미지, RGB SHA-256 비교, 약 2초).
  - 검증 도구: debug 빌드 전용 `ClipTestActivity` + `tools/check-win-clipboard.ps1`
  - ClipCascade 동작 원리: 백그라운드 클립보드 읽기 거부 logcat(ClipboardService) 감지 → ClipboardFloatingActivity가 포커스를 얻어 primaryClip 읽기 →
    content URI 1개 + getType image/* 이면 이미지로 전송
- Build: Gradle 8.11.1 wrapper, AGP 8.7.3, Kotlin 2.0.21, compileSdk/targetSdk 35, minSdk 30. 외부 라이브러리 의존성 없음.
- Phase 2–4 (2026-11-21): 구현 및 실기기 테스트 완료. 결과와 한계는 README.md "검증 결과" / "알려진 제한" 참고.
  - 화면 가장자리 드래그를 SystemUI가 가로채는 문제 → 선택 오버레이에서 시스템 바 숨김(immersive)으로 해결
  - 설정 앱(com.android.settings)이 포그라운드일 때 ClipCascade가 클립보드 읽기를 거부당함 (ClipCascade 측 한계, 다른 앱은 정상)
  - Git Bash에서 `adb shell uiautomator dump /sdcard/...`처럼 인자가 /로 시작하면 경로가 변환됨 → 명령 전체를 하나의 따옴표 문자열로 전달
- 미검증: OHO+ 핸들 실제 실행, 빠른 설정 타일 (사용자 확인 필요)
