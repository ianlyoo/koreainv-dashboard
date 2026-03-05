# Korea Investment Dashboard

Current release: `v1.2.14`

?쒓뎅?ъ옄利앷텒 API 湲곕컲 媛쒖씤 怨꾩쥖 ??쒕낫?쒖엯?덈떎.

## ?ъ슜?먯슜 ?ㅼ튂 (Windows, Python 遺덊븘??

1. [Releases](https://github.com/ianlyoo/koreainv-dashboard/releases)?먯꽌 理쒖떊 `KISDashboard-win64.zip` ?ㅼ슫濡쒕뱶
2. ?뺤텞 ?댁젣 ??`KISDashboard.exe` ?ㅽ뻾
3. 釉뚮씪?곗??먯꽌 `http://127.0.0.1:8000` ?먮룞 ?ㅽ뵂 ?뺤씤
4. 泥??붾㈃?먯꽌 `APP_KEY`, `APP_SECRET`, 怨꾩쥖踰덊샇, PIN ?ㅼ젙

?먯꽭???ㅼ튂 臾몄꽌:
- `README_INSTALL_WINDOWS.md`

## ?먮룞 ?낅뜲?댄듃

- ???쒖옉 ??GitHub Releases 理쒖떊 踰꾩쟾???뺤씤?⑸땲??
- ??踰꾩쟾???덉쑝硫??낅뜲?댄듃 ?뺤씤 李쎌씠 ?쒖떆?⑸땲??
- ?뱀씤?섎㈃ ?낅뜲?댄듃 ?뚯씪???ㅼ슫濡쒕뱶?????깆씠 ?먮룞 ?ъ떆?묐맗?덈떎.

## ?곗씠??寃쎈줈

- ?ㅼ젙 ?뚯씪: `%APPDATA%\KISDashboard\settings.json`
- ?곗쿂 濡쒓렇: `%APPDATA%\KISDashboard\logs\launcher.log`
- ?낅뜲?댄듃 濡쒓렇: `%APPDATA%\KISDashboard\logs\updater.log`

## 媛쒕컻 ?ㅽ뻾

```bash
python -m app.main
```

釉뚮씪?곗??먯꽌 `http://127.0.0.1:8000` ?묒냽

## Windows 諛고룷 鍮뚮뱶

```bat
build_windows.bat
```

寃곌낵臾?
- `dist\KISDashboard\`
- `dist\KISDashboard-win64.zip`

## ?쒓렇 湲곕컲 ?먮룞 由대━??
```bash
git tag v1.2.13
git push origin v1.2.13
```

GitHub Actions媛 Windows 鍮뚮뱶瑜??섑뻾?섍퀬 Releases??zip???먮룞 ?낅줈?쒗빀?덈떎.

## 二쇱쓽?ы빆

- 蹂??꾨줈?앺듃???ъ옄 蹂댁“ ?꾧뎄?대ŉ ?ъ옄 ?먯떎?????梨낆엫??吏吏 ?딆뒿?덈떎.
- API/怨꾩쥖?뺣낫媛 ?ы븿???ㅼ젙 ?뚯씪? ?몃?濡?怨듭쑀?섏? 留덉꽭??

## macOS 배포 빌드

macOS는 PyInstaller 기반으로 배포용 앱 번들을 생성합니다.

```bash
./scripts/build_mac_app.sh
```

생성 결과:
- `dist/KISDashboard.app`
- `dist/KISDashboard-mac-<arch>.zip`

동작:
- 앱 실행 시 FastAPI 서버(`127.0.0.1:8000`)를 띄우고 준비되면 브라우저를 자동으로 엽니다.
- 시작 시 GitHub Releases 최신 버전을 확인하고, 새 버전이 있으면 앱 내 업데이트를 진행할 수 있습니다.
- 업데이트 파일은 `~/Library/Application Support/KISDashboard/updates`에 저장됩니다.

무료 계정 기준 배포 참고:
- 현재 mac 앱은 ad-hoc 서명입니다(Developer ID/Notarization 없음).
- 다른 사용자가 다운로드한 앱이 차단되면 아래 명령으로 quarantine 속성을 제거할 수 있습니다.
  ```bash
  xattr -dr com.apple.quarantine /Applications/KISDashboard.app
  ```
