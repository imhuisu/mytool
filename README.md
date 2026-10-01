# 내 도구함

폰에 앱처럼 설치해서 쓰는 개인 도구 모음. 서버 없이 무료로 운영된다.

- 코드: GitHub
- 배포: Cloudflare Pages (push하면 1분 안에 자동 반영)
- 설치: 폰 크롬에서 열고 "앱 설치"

---

## 1. 로컬에서 먼저 보기

VS Code에서 이 폴더를 열고 터미널에:

```
python -m http.server 8000
```

브라우저에서 http://localhost:8000 을 연다.
(파일을 더블클릭해서 여는 방식은 오프라인 기능이 동작하지 않는다)

## 2. GitHub에 올리기

1. github.com 로그인 → 오른쪽 위 **+** → **New repository**
2. 이름 예: `my-tools` → **Create repository** (Public/Private 둘 다 됨)
3. VS Code 터미널에서:

```
git init
git add .
git commit -m "도구함 시작"
git branch -M main
git remote add origin https://github.com/<내아이디>/my-tools.git
git push -u origin main
```

## 3. Cloudflare Pages 연결 (처음 한 번)

1. dash.cloudflare.com 가입/로그인
2. **Workers & Pages** → **Create** → **Pages** 탭 → **Connect to Git**
3. GitHub 연결 → `my-tools` 저장소 선택
4. 빌드 설정:
   - Framework preset: **None**
   - Build command: **비워둠**
   - Build output directory: **/**
5. **Save and Deploy**

끝나면 `https://my-tools-xxx.pages.dev` 같은 주소가 나온다. 이게 내 앱 주소.

> Cloudflare 화면 문구는 조금씩 바뀔 수 있다. 핵심은 "Pages 프로젝트를 Git 저장소에 연결, 빌드 없음, 출력 폴더는 루트".

## 4. 폰에 설치

1. 폰 **크롬**에서 위 주소 열기
2. 첫 화면 오른쪽 위 **앱 설치** 버튼 (안 보이면 ⋮ 메뉴 → **앱 설치** 또는 **홈 화면에 추가**)
3. 홈 화면 아이콘으로 실행하면 주소창 없는 앱 화면으로 뜬다

설치 후에는 도구 화면 왼쪽 아래 둥근 버튼으로 도구함 첫 화면에 돌아올 수 있다.

## 5. 고치고 반영하기

```
git add .
git commit -m "골프 뽑기 이름 수정 기능"
git push
```

push하면 Cloudflare가 자동 배포한다. 폰 앱은 온라인일 때 열면 최신 버전이 뜬다.
오프라인이면 마지막으로 받아둔 버전이 뜬다.

## 6. 새 도구 추가

1. `_template` 폴더를 복사해서 이름 바꾸기 (예: `memo`)
2. `memo/index.html` 내용 작성
3. 첫 화면 `index.html`의 `TOOLS`에 한 줄 추가:

```js
{ href:'memo/', icon:'📝', name:'메모', desc:'간단 메모', tint:'#eef' },
```

## 7. 기존 데이터 옮기기

주소가 바뀌어서 기존 Claude 페이지에 저장된 기록은 자동으로 넘어오지 않는다.

- **아이들슬레이어 도구상자**: 기존 페이지에서 **설정 내보내기** → 나온 글 전체 복사 → 새 앱에서 **설정 가져오기**에 붙여넣기. 측정 기록까지 옮겨진다
- 골프 뽑기, 서풍 지도: 진행 상태만 저장돼 있어서 새로 시작하면 된다

## 주의

- **주소를 아는 사람은 누구나 볼 수 있는 공개 사이트다.** 회사 자료나 개인정보는 넣지 않는다
- 저장 데이터는 기기(브라우저)마다 따로다. PC와 폰을 동기화하려면 나중에 Cloudflare Workers + KV를 붙이면 된다
