# Release Policy (Mandatory vs Recommended Update)

이 문서는 릴리스 시 앱의 업데이트 동작을 어떻게 제어하는지 정의합니다.

## 정책 요약

- `필수 업데이트 (mandatory)`
  - Windows/macOS 공통으로 앱 시작 시 즉시 업데이트를 진행합니다.
  - 사용자가 시작 시점에 건너뛸 수 없습니다.
- `권장 업데이트 (recommended, 기본값)`
  - 앱 시작 시 업데이트 팝업이 뜨지 않습니다.
  - 앱 메뉴의 `업데이트 확인`에서만 업데이트를 선택할 수 있습니다.

## 필수 업데이트로 릴리스하는 방법

GitHub Release 본문에 아래 키워드 중 하나를 포함하면 `필수 업데이트`로 인식됩니다.

- `mandatory-update`
- `update_policy: mandatory`
- `필수 업데이트`

권장 업데이트로 릴리스하려면 위 키워드를 넣지 않으면 됩니다.

## 릴리스 본문 예시

필수 업데이트 예시:

```md
## 변경 사항
- 로그인 안정성 개선
- 업데이트 파일 검증 강화

mandatory-update
```

권장 업데이트 예시:

```md
## 변경 사항
- UI 개선
- 성능 최적화
```

## 업데이트 UX 참고

- macOS 앱 메뉴: `업데이트 확인`
- Windows 트레이 메뉴: `업데이트 확인`

## 릴리스 체크리스트

1. `app/version.py` 버전 업데이트
2. 앱 빌드 수행 (`build_windows.bat`, `./scripts/build_mac_app.sh`)
3. 태그 생성/푸시 (`git tag vX.Y.Z`, `git push origin vX.Y.Z`)
4. GitHub Release 본문에 정책 키워드 포함 여부 확인
5. 릴리스 후 실제 업데이트 동작(필수/권장) 점검
