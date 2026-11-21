# AndroidSnip

Android용 가벼운 부분 화면 캡처 앱 (Windows **Win + Shift + S** 대응).
영역을 드래그해서 손을 떼는 즉시 PNG 이미지가 클립보드에 복사되며, [ClipCascade](https://github.com/Sathvik-Rao/ClipCascade)를 통해 PC에서 바로 Ctrl+V로 붙여넣을 수 있습니다.

- Galaxy Tab S7+ (SM-T970, Android 13 / One UI 5.1.1)에서 개발·검증
- 권한 선언 0개 (인터넷 권한 없음), 외부 라이브러리 없음, 루팅 불필요, APK 약 54 KB
- 접근성 서비스는 `takeScreenshot` 기능만 사용합니다 (화면 내용 읽기·이벤트 수신 없음)

## 설치

```
adb install -r dist/AndroidSnip-v0.1.apk
```

1. 설정 › 접근성 › 설치된 앱 › **AndroidSnip** 켜기
2. Good Lock › One Hand Operation+ › 핸들 동작 하나에 **앱 실행 › "Snip 캡처"** 할당
   (또는 "바로가기" › AndroidSnip "영역 캡처")
3. (선택) 빠른 설정 패널 편집에서 **Snip** 타일 추가

> ⚠️ 앱을 **강제 중지**하면 Android가 접근성 서비스를 자동으로 끕니다. 그 경우 1번을 다시 해야 합니다.

## 사용법

| 동작 | 결과 |
|---|---|
| 드래그 후 손 떼기 (Auto Copy, 기본) | 즉시 클립보드에 복사 |
| 드래그 없이 탭 / 뒤로가기 | 취소 |
| Adjust 모드 (앱 설정에서 켬) | 드래그 후 모서리·변·내부를 끌어 조정 → **복사** 버튼 또는 영역 안 더블탭 |
| 선택 중 화면 회전 | 자동 취소 |

손가락과 S펜 모두 지원합니다. 복사되면 One UI가 "클립보드에 복사되었어요." 토스트를 띄웁니다.

## 동작 방식

1. 진입점(`CaptureActivity`, `Theme.NoDisplay`)은 화면을 그리지 않고 접근성 서비스에 캡처를 요청한 뒤 즉시 종료합니다.
2. `SnipService`가 250 ms 후 `AccessibilityService.takeScreenshot()`으로 화면을 찍습니다. **이 시점에는 앱의 창이 하나도 없으므로** 오버레이·테두리가 이미지에 들어갈 수 없습니다.
3. 그 정지 화면 위에 `TYPE_ACCESSIBILITY_OVERLAY` 선택 창을 띄웁니다. 선택 중에는 실제 시스템 바를 숨겨서(정지 화면에는 그대로 보임) 화면 가장자리에서 시작하는 드래그를 SystemUI가 가로채지 않게 합니다.
4. 선택 영역을 Bitmap에서 잘라 `cache/shots/`에 PNG로 저장하고, `ClipData.newUri()`로 `content://com.androidsnip.shots/<file>` (`image/png`, `grantUriPermissions`)를 클립보드에 등록합니다. 임시 파일은 최근 3개만 유지합니다.

## 빌드

요구 사항: JDK 17 이상, Android SDK (Platform 35, Build-Tools 35.0.0). `local.properties`에 `sdk.dir`을 지정하거나 `ANDROID_HOME`을 설정합니다.

```
gradlew.bat assembleRelease     # app/build/outputs/apk/release/app-release.apk
gradlew.bat assembleDebug       # 디버그 빌드 (테스트용 액티비티 포함)
```

릴리스 빌드는 R8로 축소되며, 바로 설치할 수 있도록 디버그 키로 서명됩니다.

## 테스트 도구

디버그 빌드에만 포함되는 액티비티와 PC 쪽 스크립트입니다.

- `ClipTestActivity`: 실행마다 고유한 테스트 PNG를 클립보드에 등록 (Phase 1 호환성 검증)
- `PatternActivity`: 픽셀마다 자기 화면 좌표를 색으로 인코딩한 패턴 표시. 잘린 이미지만으로 정확한 원점과 섞인 픽셀 수를 검증할 수 있음
- `tools/check-win-clipboard.ps1 -Expected x.png`: Windows 클립보드 이미지가 기대 PNG와 RGB 픽셀 단위로 일치할 때까지 대기
- `tools/analyze-pattern.ps1 -Image x.png`: 패턴 이미지에서 원점, 크기, 불일치 픽셀 수 분석
- `tools/crop-png.ps1`: `adb screencap` 결과를 잘라 독립 기준 이미지 생성

테스트에서 `am start -S` / `am force-stop`을 쓰지 마세요 (접근성 서비스가 꺼집니다).

## 검증 결과 (실기기 ADB 자동 테스트, 2026-11-21)

| 항목 | 결과 |
|---|---|
| 클립보드 → ClipCascade → Windows 이미지 전달 | ✅ 고유 이미지 5/5회 픽셀 일치, 약 2초 |
| 가로·세로, 정방향·역방향 드래그, 전체 화면 | ✅ 좌표·크기 정확, 오버레이 픽셀 0 |
| S펜(stylus) 입력 | ✅ |
| 상태바·내비게이션 바 영역에서 시작/끝나는 드래그 | ✅ (시스템 바 숨김 적용 후) |
| Adjust 모드 (크기 조정·이동·복사 버튼) | ✅ |
| 탭/뒤로가기 취소, 선택 중 회전 시 취소 | ✅ |
| 실제 앱 (계산기, 내 파일, 캘린더, 시계, 홈) → Windows | ✅ (릴리스 APK는 `screencap` 기준 이미지와 픽셀 일치) |
| 팝업 메뉴 같은 일시적 UI | ✅ 캡처 시점까지 유지되어 이미지에 포함 |

## 알려진 제한 / 미검증

- **설정 앱 위에서 캡처하면 ClipCascade가 이미지를 전송하지 못합니다.** 안드로이드 클립보드 복사 자체는 정상입니다. ClipCascade는 오버레이 기반 `ClipboardFloatingActivity`가 포커스를 얻어야 클립보드를 읽을 수 있는데, 설정 앱 위에서는 그 읽기가 거부됩니다 (logcat `Denying clipboard access to com.clipcascade`). 원인은 설정 앱이 다른 앱의 오버레이 창을 숨기기 때문으로 추정되며 확인하지는 못했습니다. ClipCascade 쪽 동작이라 이 앱에서는 해결할 수 없습니다.
- **미검증: One Hand Operation+ 핸들에서의 실제 실행.** ADB로는 같은 Intent(`am start -n com.androidsnip/.CaptureActivity`)로만 테스트했습니다. 핸들 애니메이션이 250 ms 안에 끝나지 않으면 이미지에 찍힐 수 있습니다.
- **미검증: 빠른 설정 타일.** 사용자의 빠른 설정 패널 구성을 바꾸지 않기 위해 타일을 추가하지 않았습니다.
- 보안 화면(`FLAG_SECURE`)은 Android 정책에 따라 검게 캡처됩니다.
- `takeScreenshot`은 1초에 1회로 제한됩니다 (너무 빨리 연속 실행하면 1초 후 자동 재시도).
