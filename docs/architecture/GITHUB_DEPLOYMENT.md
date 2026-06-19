# GitHub Actions 배포 셋업 가이드

> **목적**: `main` 브랜치 병합 시 EC2에 자동 배포되도록 하는 CI/CD 파이프라인의 **사전 준비·시크릿·동작 방식**을 정리한다.
> **대상 워크플로**: [`.github/workflows/dev.yml`](../../.github/workflows/dev.yml)
> **인프라 설계**: [`AWS_DEPLOYMENT.md`](AWS_DEPLOYMENT.md)

---

## 1. 배포 개요

`main` 브랜치에 코드가 병합되면 GitHub Actions가 **이미지 빌드 → GHCR push → EC2 SSH 배포**를 자동 수행한다. EC2에서는 Gradle 빌드를 하지 않고 **이미지 pull**만 하므로 t4g.small(2GB) 메모리 부담이 없다.

```
  PR → develop/main  ┌─────────────────────────────────────────────────┐
  ────────────────▶ │ test (Gradle)  +  container-check                 │  ← 검증만, 배포 안 함
                     │   container-check: compose 검증 → 이미지 빌드       │
                     │                    → mysql+redis+app 기동 → ping   │
                     └─────────────────────────────────────────────────┘

  push → develop     ┌──────────┐
  ────────────────▶ │  test     │                                          ← 통합 검증만, 배포 안 함
                     └──────────┘

  push → main        ┌──────────┐   ┌────────────────────┐   ┌──────────────────────────┐
  ────────────────▶ │  test     │──▶│ arm64 빌드 → GHCR push │──▶│ EC2 SSH: pull → up -d (:sha) │
   (병합 완료)        └──────────┘   └────────────────────┘   └──────────────────────────┘
                                              │                            │
                                    :latest(포인터) + :<sha>       IMAGE_TAG=<sha>로 고정 배포
                                                                  git pull + compose pull/up + prune
```

> **검증은 develop·main 공통, 배포는 main 한정**이다. develop 통합 단계에서 미리 테스트·컨테이너 검증을 돌려 main 병합 전에 문제를 거른다.

> 배포는 **`:latest`가 아니라 해당 커밋의 `:<sha>` 이미지로 고정**해 내려가며, `concurrency`로 동시 배포를 직렬화한다(아래 §6). `:latest`는 수동/롤백용 포인터일 뿐이다.

> **브랜치 전략과의 관계**: Git Flow에서 `main`은 배포본이다(`GIT_CONVENTION.md §1`). `release/*` 또는 `hotfix/*`가 `main`에 머지되는 시점에 배포가 트리거된다.

---

## 2. 사전 준비 체크리스트

배포가 처음 동작하려면 아래가 모두 준비되어야 한다.

- [ ] **EC2 인스턴스** 기동 (t4g.small, Ubuntu Server 24.04) — `AWS_DEPLOYMENT.md §3, §6.2`
- [ ] EC2에 **Docker · docker compose · git 설치** — `AWS_DEPLOYMENT.md §6.2`
- [ ] EC2에 **저장소 clone** (배포 경로 = `DEPLOY_PATH`) — 아래 §4
- [ ] EC2에 **`.env` 파일 배치** (저장소 루트) — 아래 §3
- [ ] **GHCR 패키지 접근 설정** (public 공개 또는 PAT 발급) — 아래 §5
- [ ] **GitHub Secrets 5종 등록** — 아래 §5
- [ ] 보안 그룹에서 **SSH(22)** 를 GitHub Actions 또는 운영자 IP에 허용 — 아래 §6 주의

---

## 3. EC2의 `.env` 위치와 구성

### 위치

`.env`는 **EC2에 clone된 저장소 루트**(= `DEPLOY_PATH`)에 둔다. `docker compose`는 compose 파일과 같은 디렉터리의 `.env`를 자동으로 읽기 때문이다.

```
${DEPLOY_PATH}/                 (예: /home/ubuntu/Backend)
├── docker-compose.yml
├── Dockerfile
├── nginx/
└── .env          ← 여기 (git에 커밋 안 됨, 수동 배치)
```

> `.env`는 `.gitignore`에 포함되어 **저장소에 절대 커밋되지 않는다**. `git pull`로 덮어써지지도 않으므로 한 번 배치하면 유지된다.

### 배치 방법

```bash
cd ${DEPLOY_PATH}
cp .env.example .env
nano .env            # 아래 값 채우기
chmod 600 .env       # 소유자만 읽기 (권한 최소화)
```

### 내용 (prod 기준)

```dotenv
ACTIVE=prod

# DB_URL/REDIS_HOST는 compose 서비스명(mysql, redis)을 호스트로 사용
DB_URL=jdbc:mysql://mysql:3306/blursome?serverTimezone=Asia/Seoul&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true
DB_USERNAME=blursome
DB_PASSWORD=<강력한-비밀번호>

REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=<강력한-비밀번호>

JWT_SECRET=<32바이트 이상 임의 문자열>
JWT_ACCESS_TOKEN_EXPIRES_IN=1800
JWT_REFRESH_TOKEN_EXPIRES_IN=1209600

KAKAO_CLIENT_ID=<카카오 REST API 키>
KAKAO_CLIENT_SECRET=<카카오 시크릿>
KAKAO_REDIRECT_URI=https://<도메인>/login/oauth/kakao

# S3 — 자격증명은 EC2 IAM Role로 자동 주입 (Access Key 넣지 않음)
AWS_REGION=ap-northeast-2
S3_BUCKET=<버킷 이름>

# (최초 1회 스키마 생성 시에만 추가, 생성 후 줄 삭제 — AWS_DEPLOYMENT.md §6.5)
# SPRING_JPA_HIBERNATE_DDL_AUTO=update
```

> `DB_PASSWORD`/`REDIS_PASSWORD`는 compose가 MySQL/Redis 컨테이너 기동에도 사용하므로, **최초 컨테이너 생성 전에** 확정해야 한다. 이미 볼륨이 생성된 뒤 비밀번호를 바꾸면 기존 데이터 볼륨과 불일치한다.

---

## 4. EC2에 저장소 clone (DEPLOY_PATH)

워크플로의 배포 단계는 EC2에서 `git pull`을 수행하므로, EC2에 저장소가 미리 clone되어 있어야 한다.

```bash
cd /home/ubuntu
git clone https://github.com/It-is-a-Secret/Backend.git
# → /home/ubuntu/Backend  (이 경로가 DEPLOY_PATH)
cd Backend
git checkout main
```

- private 저장소라면 clone/pull용 인증이 필요하다. **배포 토큰(read-only PAT)** 또는 **deploy key(읽기 전용 SSH 키)** 등록을 권장한다.
- `DEPLOY_PATH`는 위 clone 경로의 절대경로(예: `/home/ubuntu/Backend`)를 그대로 GitHub Secret으로 등록한다.

---

## 5. GitHub Actions에 필요한 필드 (Secrets)

`Settings → Secrets and variables → Actions → New repository secret`에서 등록한다.

| 시크릿 이름 | 필수 | 설명 | 예시 / 얻는 법 |
|---|:---:|---|---|
| `EC2_HOST` | ✅ | 배포 대상 EC2의 공인 IP 또는 도메인 | `3.34.xxx.xxx` 또는 `api.blursome.dev` |
| `EC2_USER` | ✅ | SSH 접속 사용자 | Ubuntu AMI 기본값 `ubuntu` |
| `EC2_SSH_KEY` | ✅ | EC2 접속용 **개인키 전문**(PEM) | 인스턴스 생성 시 받은 `.pem` 파일 내용 전체(`-----BEGIN ... END-----` 포함) |
| `DEPLOY_PATH` | ✅ | EC2에 clone된 저장소 절대경로 | `/home/ubuntu/Backend` (§4) |
| `GHCR_PAT` | △ | EC2가 GHCR 이미지를 pull할 때 쓰는 토큰 | GitHub PAT(`read:packages`). **GHCR 패키지를 public으로 두면 불필요** |
| `GHCR_USERNAME` | △ | `GHCR_PAT` 소유자의 GitHub 사용자명 | `GHCR_PAT`와 **한 쌍**으로 등록. 둘 중 하나라도 비면 로그인 단계를 건너뜀(public 전제) |

> **`GHCR_PAT`/`GHCR_USERNAME`은 둘 다 있을 때만** EC2에서 `docker login`을 수행한다(워크플로 §6의 조건부 로그인). 패키지를 public으로 두면 둘 다 비워도 되고, 그 경우 로그인 없이 pull한다. PAT 소유자와 워크플로 실행자(`github.actor`)가 다를 수 있어 사용자명을 별도 시크릿으로 분리했다.
>
> **`GITHUB_TOKEN`** 은 별도 등록하지 않는다. Actions가 자동 주입하며, 워크플로의 `permissions: packages: write`로 GHCR **push** 권한을 얻는다.

### `EC2_SSH_KEY` 등록 요령

```bash
# 로컬에서 개인키 전체를 클립보드로 복사해 시크릿 값으로 붙여넣는다.
cat blursome-key.pem
# -----BEGIN RSA PRIVATE KEY----- ... -----END RSA PRIVATE KEY-----  전체
```

### GHCR 접근: public vs PAT

- **(간단) 패키지 public 공개**: 첫 배포 후 GHCR 패키지 페이지 → `Package settings → Change visibility → Public`. 이러면 `GHCR_PAT` 없이 누구나 pull 가능(코드가 아니라 빌드 이미지만 공개됨).
- **(보안) private 유지 + PAT**: `read:packages` 스코프 PAT를 발급해 `GHCR_PAT`로 등록. 워크플로 배포 단계가 EC2에서 `docker login ghcr.io`에 사용한다.

---

## 6. 워크플로 동작 방식 (단계별)

`dev.yml`은 세 잡(job)으로 구성된다.

### job 1: `test` (develop·main 의 push·PR 공통)
1. 저장소 체크아웃
2. JDK 21(temurin) 설정 + Gradle 캐시
3. `./gradlew test` 실행 — 실패 시 배포 잡이 실행되지 않는다

### job 2: `container-check` (develop·main 대상 PR 에서만)
Dockerfile/Compose 오류를 **main 병합 전에** 잡는다.
1. `docker compose config -q` — compose 문법 검증
2. `docker build`(amd64, 로컬 로드) — Dockerfile 빌드 검증 (buildx GHA 캐시)
3. CI용 `.env` 생성 후 `docker compose ... up -d mysql redis app` 기동
4. `curl http://localhost:8080/api/ping` 가 `pong`을 반환하는지 폴링(최대 ~200초)
5. `always()`로 `compose down -v` 정리

> 스모크 테스트는 러너(amd64)에서 수행한다. 산출 jar는 아키텍처 독립적이므로 arm64 런타임에서도 동일하게 기동한다. 포트 노출은 `docker-compose.ci.yml` 오버라이드로만 적용된다(운영 compose는 app을 외부에 노출하지 않음).

### job 3: `build-and-deploy` (`push → main` 에서만)
1. `test` 통과를 선행 조건(`needs: test`)으로 함
2. `concurrency: group=deploy-main, cancel-in-progress=false` — 동시 배포 직렬화
3. QEMU + buildx 준비 (arm64 크로스 빌드)
4. `GITHUB_TOKEN`으로 GHCR 로그인 (push 권한)
5. `linux/arm64` 이미지 빌드 → `:latest` + `:<커밋 SHA>` 태그로 GHCR push (buildx GHA 캐시 사용)
6. `EC2_SSH_KEY`로 EC2 SSH 접속, `IMAGE_TAG=<커밋 SHA>`를 환경으로 전달 후:
   ```bash
   cd "$DEPLOY_PATH"
   git fetch --all && git checkout main && git pull --ff-only
   # PAT·USERNAME이 모두 있을 때만 로그인 (public 패키지면 건너뜀)
   if [ -n "$GHCR_PAT" ] && [ -n "$GHCR_USERNAME" ]; then
     echo "$GHCR_PAT" | docker login ghcr.io -u "$GHCR_USERNAME" --password-stdin
   fi
   export IMAGE_TAG="$IMAGE_TAG"   # 이번 커밋 :<sha> 로 고정 (latest 레이스 회피)
   docker compose pull        # 해당 SHA 이미지 받기
   docker compose up -d        # 변경된 컨테이너만 재기동
   docker image prune -f       # 미사용 이미지 정리(디스크 절약)
   ```

> **SSH 접근 주의**: GitHub Actions 러너의 IP는 고정되지 않는다. 보안 그룹 22번을 `0.0.0.0/0`으로 여는 것은 위험하므로, 가능하면 **AWS SSM Session Manager 기반 배포** 또는 **운영자 IP 화이트리스트 + 수동 배포 병행**을 검토한다. 개발용으로 부득이 열 경우 키 인증만 허용하고 비밀번호 로그인은 비활성화한다.

---

## 7. 최초 배포(부트스트랩) 절차

자동화가 돌기 전, EC2에서 1회 수동으로 기동해 스키마를 만든다.

```bash
cd ${DEPLOY_PATH}
# 1) .env에 SPRING_JPA_HIBERNATE_DDL_AUTO=update 추가 (AWS_DEPLOYMENT.md §6.5)
# 2) 최초 기동 (로컬 빌드 또는 GHCR pull)
docker compose up -d --build      # 또는: docker compose pull && docker compose up -d
# 3) 스키마 생성·정상 동작 확인 후 .env에서 update 줄 삭제 → 재기동
docker compose up -d
```

이후부터는 `main` 병합 시 워크플로가 자동 배포한다.

---

## 8. 배포 확인

```bash
# 서버 자체
curl http://localhost:8080/api/ping       # {"data":"pong", ...}
# Nginx 경유 (외부)
curl http://<EC2_HOST>/api/ping
# 컨테이너 상태
docker compose ps
docker compose logs -f app
```

`ping/pong` 엔드포인트는 인증 없이 호출 가능하며(`SecurityConfig` permitAll), 배포 성공 여부를 가장 빠르게 확인하는 수단이다.

---

## 9. 트러블슈팅

| 증상 | 원인 / 조치 |
|---|---|
| 배포 잡이 안 돔 | `main` **push(병합)** 에서만 실행됨. PR 단계에서는 test·container-check만 수행 |
| PR `container-check` 실패 | compose/Dockerfile 오류 또는 app 부팅 실패. 잡 로그의 `docker compose logs app` 확인 |
| 스모크 테스트 타임아웃 | MySQL 헬스체크 지연 가능. app이 DB 연결에 실패하는지 로그 확인(CI .env의 `DB_*` 정합성) |
| GHCR push 403 | 워크플로 `permissions: packages: write` 확인, GHCR 로그인 단계 확인 |
| EC2에서 pull 401/denied | 패키지가 private인데 `GHCR_PAT`/`GHCR_USERNAME` 미등록·불일치·만료 → 재발급 또는 패키지 public 전환 |
| SSH 단계 timeout | 보안 그룹 22번 인바운드, `EC2_HOST`/`EC2_USER`/키 값 확인 |
| `git pull` 충돌/실패 | 서버에서 로컬 변경이 있는지 확인. `.env` 외 추적 파일을 서버에서 수정하지 말 것 |
| app이 DB/Redis 연결 실패 | `.env`의 `DB_URL`/`REDIS_HOST`가 compose 서비스명(`mysql`,`redis`)인지 확인 |
| 비밀번호 바꿨는데 DB 인증 실패 | 기존 `mysql-data` 볼륨과 불일치. 개발 초기엔 볼륨 삭제 후 재생성(`docker compose down -v`, **데이터 소실 주의**) |

---

## 10. 롤백

특정 커밋 이미지로 되돌리려면 SHA 태그를 사용한다.

```bash
cd ${DEPLOY_PATH}
IMAGE_TAG=<되돌릴-커밋-SHA> docker compose pull
IMAGE_TAG=<되돌릴-커밋-SHA> docker compose up -d
```

`docker-compose.yml`의 `image: ghcr.io/it-is-a-secret/blursome:${IMAGE_TAG:-latest}` 가 `IMAGE_TAG` 환경변수를 받는다(미지정 시 `latest`).
