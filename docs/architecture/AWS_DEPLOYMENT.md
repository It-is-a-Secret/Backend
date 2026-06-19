# AWS 최소 비용 배포 아키텍처 (개발용)

> **목적**: 실서비스 운영이 아닌 **개발/검증 기간** 동안 BlurSome 백엔드를 AWS에 **최소 비용**으로 배포한다.
> **핵심 전략**: 단일 EC2 인스턴스 1대에 애플리케이션 · MySQL · **Redis(EC2 내부 설치)** 를 모두 올려 관리형 서비스(RDS/ElastiCache)
> 비용을 0으로 만든다.
>
> ⚠️ **과금 전제**: 본 계정은 **프리티어가 만료된 기존 계정**이다. 모든 항목은 **종량제(pay-as-you-go)로 실제 비용이 발생**한다. 따라서 "
> 프리티어로 $0"가 아니라 **절대 비용을 최소화하는 선택**(가장 저렴한 ARM 인스턴스, 최소 EBS, 공인 IPv4 1개, 미사용 시 중지)을 기준으로 설계한다.
>
> 운영 등급(고가용성·자동 백업·수평 확장)으로 전환할 때의 변경점은 [§9 운영 전환 시 고려사항](#9-운영-전환-시-고려사항)에 정리한다.

---

## 1. 설계 원칙

| 원칙       | 결정                                                      |
|----------|---------------------------------------------------------|
| 비용 최소화   | 관리형 서비스(RDS, ElastiCache, ALB) 사용 안 함. 단일 EC2에 전부 수용    |
| 인스턴스     | **ARM(Graviton, t4g 계열)** 우선 — 동급 x86(t3) 대비 약 20% 저렴   |
| 절대 비용 절감 | 프리티어 없음 전제. EBS 최소화 · 공인 IPv4 1개 · 미사용 시간 인스턴스 중지       |
| Redis    | **EC2 내부에 Docker 컨테이너로 설치** (ElastiCache 미사용)           |
| MySQL    | **EC2 내부에 Docker 컨테이너로 설치** (RDS 미사용)                   |
| 무중단/이중화  | 개발용이므로 **포기**. 단일 인스턴스, 단일 AZ                           |
| 배포 단순성   | Docker Compose 1개 파일로 app + MySQL + Redis + Nginx 일괄 기동 |

---

## 2. 전체 아키텍처

```
                        인터넷
                          │
                  ┌───────┴────────┐
                  │  Route 53 또는   │   (선택: 무료 도메인/서브도메인)
                  │  무료 DNS 서비스  │
                  └───────┬────────┘
                          │  HTTPS (443) / HTTP(80→443 리다이렉트)
                          ▼
          ┌──────────────────────────────────────────┐
          │           EC2 인스턴스 (단일, 단일 AZ)         │
          │   ap-northeast-2 (서울) · t4g.small (2GB, ARM) │
          │   Ubuntu Server 24.04 LTS (HVM, SSD)         │
          │                                            │
          │   ┌──────────────────────────────────┐     │
          │   │  Nginx (리버스 프록시 · TLS 종료)      │     │
          │   │   - 80 → 443 리다이렉트              │     │
          │   │   - WebSocket Upgrade 프록시         │     │
          │   │   - Let's Encrypt 인증서            │     │
          │   └───────────────┬──────────────────┘     │
          │                   │ 127.0.0.1:8080         │
          │   ┌───────────────▼──────────────────┐     │
          │   │  Spring Boot App (Docker)          │     │
          │   │   blursome:prod                    │     │
          │   └─────┬─────────────────┬───────────┘     │
          │         │ 127.0.0.1:3306  │ 127.0.0.1:6379  │
          │   ┌─────▼──────┐   ┌──────▼──────┐          │
          │   │  MySQL      │   │  Redis       │          │
          │   │  (Docker)   │   │  (Docker)    │          │
          │   │  볼륨 영속화  │   │  AOF 영속화   │          │
          │   └────────────┘   └─────────────┘          │
          │                                            │
          │   EBS gp3 20GB (루트 + 데이터 볼륨)             │
          └──────────────────────────────────────────┘
                  │                       │
          ┌───────┴────────┐     ┌────────┴─────────┐
          │  공인 IPv4 1개    │     │  S3 (별도 버킷)     │
          │  (유료, ~$3.6/월) │     │  IAM Role로 접근    │
          └────────────────┘     └──────────────────┘
```

모든 데이터 컴포넌트(MySQL/Redis)는 **`127.0.0.1`(루프백)에만 바인딩**하여 외부에서 직접 접근할 수 없게 한다. 외부 노출은 Nginx(443)만 허용한다.
파일/이미지 등 객체 저장은 EC2 디스크가 아니라 **별도 S3 버킷**을 사용한다(§3.5).

---

## 3. 컴포넌트 구성

### 3.1 EC2 인스턴스

| 항목      | 권장값                                                        | 비고              |
|---------|------------------------------------------------------------|-----------------|
| 리전      | `ap-northeast-2` (서울)                                      | 카카오 API 레이턴시 최소 |
| 인스턴스 타입 | **`t4g.small`** (ARM 2GB, 2vCPU)                           | 확정              |
| OS      | **Ubuntu Server 24.04 LTS (HVM), SSD Volume Type** (arm64) | 확정. 표준판(Pro 아님) |
| 스토리지    | EBS gp3 **20GB**                                           | 비용 최소화를 위해 20GB |
| 네트워크    | 퍼블릭 서브넷 + 공인 IPv4 1개                                       | IPv4 유료(§4)     |
| 객체 저장   | **S3 별도 버킷**                                               | 파일/이미지 등 (§3.5) |

**인스턴스 메모리**: t4g.small(2GB)은 Java 21 JVM + MySQL + Redis를 함께 올리기에 여유가 있다. 그래도 안전을 위해 JVM 힙 상한(
`-Xmx512m` 내외)과 MySQL `innodb-buffer-pool-size`(128~256M) 설정을 권장한다. 스왑(1~2GB)도 보험으로 추가해두면 좋다.

> **OS 선택 근거**: 비용 최소화를 위해 시간당 구독료가 붙는 *Ubuntu Pro* 대신 **표준 Ubuntu Server 24.04 LTS**를 사용한다(Pro
> 구독료 $0). 개발용으로는 Pro의 ESM·Livepatch가 필요하지 않다.
>
> ARM 인스턴스이므로 Docker 이미지는 반드시 **`arm64`로 빌드**한다(§6.6).

### 3.2 Redis (EC2 내부)

- Docker 컨테이너(`redis:7-alpine`)로 기동, `127.0.0.1:6379`만 노출
- 용도: JWT 리프레시 토큰 저장(`blursome:member:<id>:refresh-token`), 캐시 (`docs/ARCHITECTURE.md §6` 참조)
- 영속화: 리프레시 토큰 유실 시 재로그인으로 복구 가능하므로 **AOF 정도면 충분**. 메모리 절약을 위해 `maxmemory 96mb` +
  `maxmemory-policy noeviction`(토큰 유실 방지) 설정
- 비밀번호: `REDIS_PASSWORD` 설정(루프백 바인딩이라도 컨테이너 격리 보강용)

### 3.3 MySQL (EC2 내부)

- Docker 컨테이너(`mysql:8.0`)로 기동, `127.0.0.1:3306`만 노출
- 데이터는 named volume 또는 별도 EBS 경로에 영속화
- **스키마 생성 전략(확정)**: 최초 1회 `ddl-auto: update`로 앱을 띄워 스키마를 자동 생성한 뒤, 그대로 사용한다. 마이그레이션 도구(Flyway 등)는
  도입하지 않는다. (상세: §6.5)

### 3.4 Nginx (리버스 프록시)

prod 프로파일이 요구하는 사항 때문에 필수:

- `app.cookie.secure=true` → **HTTPS 필수** (리프레시 토큰 쿠키 전송)
- 채팅 WebSocket → `wss://` + `Upgrade` 헤더 프록시 필요
- 카카오 OAuth `redirect-uri` → 공인 도메인/HTTPS 권장

TLS 인증서는 **Let's Encrypt(certbot, 무료)** 사용. 도메인이 없으면 무료 서브도메인(예: DuckDNS, nip.io) 또는 저렴한 도메인 사용.

### 3.5 S3 (객체 저장, 별도 사용)

파일·이미지 등 객체 저장은 EC2 디스크가 아니라 **별도 S3 버킷**을 사용한다(EBS 용량·비용 절약 + 내구성).

- **접근 방식(권장)**: EC2에 **IAM Role(인스턴스 프로파일)**을 부여해 애플리케이션이 액세스 키 없이 S3에 접근한다. 서버 `.env`에 장기 자격증명(
  Access Key/Secret)을 넣지 않는다.
- **버킷 정책**: 퍼블릭 차단(Block Public Access) 유지. 공개가 필요한 정적 파일은 CloudFront 또는 사전 서명 URL(presigned URL)로
  노출한다.
- **권한 최소화**: IAM Role에는 해당 버킷에 대한 `s3:GetObject`/`s3:PutObject`/`s3:DeleteObject` 등 필요한 액션만 부여한다.
- **리전**: EC2와 동일 리전(`ap-northeast-2`)에 두어 동일 리전 전송 요금을 0으로 한다.
- **참고**: 현재 코드베이스에는 아직 S3 연동(AWS SDK) 의존성이 없다. 객체 저장 기능 도입 시 `software.amazon.awssdk:s3` 추가가 필요하다.

---

## 4. 비용 추정 (서울 리전 ap-northeast-2, 종량제 · 프리티어 없음)

> 모든 수치는 **온디맨드 Linux 기준 근사치**다. 환율·요금 개정에 따라 달라질 수
> 있으니 [AWS Pricing Calculator](https://calculator.aws/)로 최종 확인한다. 730시간 = 1개월 상시 가동 기준.

### 4.1 상시 가동(24/7) 월 비용

**확정 구성: t4g.small + Ubuntu Server 24.04(표준) + S3 별도**

| 항목                          | 단가(약)                        | 월 비용(약)                 |
|-----------------------------|------------------------------|-------------------------|
| EC2 **t4g.small** (2GB, 상시) | $0.0208/시간                   | **$15.2**               |
| OS Ubuntu Server(표준)        | 구독료 없음                       | **$0**                  |
| EBS gp3 20GB                | $0.092/GB·월                  | $1.8                    |
| 공인 IPv4 1개                  | $0.005/시간                    | $3.6                    |
| 데이터 전송(아웃바운드)               | 100GB/월까지 무료, 초과분 ~$0.126/GB | $0 (개발 트래픽 가정)          |
| S3 (별도 버킷)                  | $0.025/GB·월 + 요청·전송          | **약 $1 내외** (개발용 소량 가정) |
| **합계**                      |                              | **약 $21~22 / 월**        |

> **공인 IPv4 유료화 주의**: 2024-02-01부터 모든 공인 IPv4 주소는 연결 여부와 무관하게 시간당 과금된다. Elastic IP든 자동 할당 IP든 **인스턴스
실행 중에는 동일하게 과금**된다. 인스턴스를 중지해도 Elastic IP를 **계속 보유**하면 idle 요금이 부과되므로, 장기 중지 시에는 EIP를 해제하거나 자동 할당
> IP를 쓴다.
>
> **S3 비용**: 저장 용량 + PUT/GET 요청 수 + 인터넷 아웃바운드 전송에 비례. EC2와 동일 리전이면 EC2↔S3 간 전송은 무료. 개발용 소량 사용은 월 $1
> 안팎으로 미미하다.

### 4.2 비용 절감 수단 (강력 권장)

| 수단                               | 절감 효과                                                  | 트레이드오프                                      |
|----------------------------------|--------------------------------------------------------|---------------------------------------------|
| **미사용 시간 인스턴스 중지**               | 컴퓨팅 요금 비례 절감 (예: 평일 12h만 ≈ 260h → t4g.small 컴퓨팅 ~$5.4) | 중지 중에도 **EBS·EIP는 과금**. 시작/종료 자동화 필요(아래)    |
| 자동 할당 공인 IP 사용                   | 중지 시 IPv4 idle 요금 0                                    | 시작할 때마다 **IP가 바뀜** → DNS/카카오 redirect 갱신 필요 |
| **Compute Savings Plan (1년 약정)** | t4g 컴퓨팅 약 30%↓                                         | 1년 사용 약정. 개발이 1년 이상이면 유효                    |
| **Spot 인스턴스**                    | 온디맨드 대비 최대 ~70%↓                                       | AWS가 회수 가능(중단). 데이터는 EBS에 있어 보존되나 가용성 불안정   |
| EBS 크기 최소화(20GB)                 | 스토리지 요금↓                                               | 디스크 여유 감소. 로그 로테이션 필수(§8)                   |
| gp3 사용(gp2 아님)                   | 동일 용량 약 20%↓ + 성능 우수                                   | 없음 (기본 채택)                                  |

**시작/종료 자동화 예시(가장 큰 절감)**: 개발 시간대에만 켜는 것이 단일 절감 수단으로 가장 효과적이다.

- **EventBridge Scheduler + Lambda**(또는 인스턴스 내 cron으로 자기 자신 `shutdown`) 로 평일 오전 시작 / 야간·주말 중지
- 예) 평일 09–21시(주 60h ≈ 월 260h)만 가동 시 t4g.small 컴퓨팅 **$15.2 → 약 $5.4**, IPv4를 자동 할당으로 쓰면
  IPv4도 ~$1.3로 감소 → **월 합계 약 $8 수준**

### 4.3 시나리오별 예상 월 비용 (확정 t4g.small 기준)

| 시나리오       | 가동     | IPv4   | 예상 월 비용      |
|------------|--------|--------|--------------|
| 확정 구성 (상시) | 24/7   | EIP 고정 | **약 $21~22** |
| 절감 (자동 중지) | 평일 주간만 | 자동할당   | **약 $9~11**  |

> 모두 t4g.small + Ubuntu Server(표준) + S3 기준. Route 53 호스팅 영역을 쓰면 영역당
> 약 $0.5/월이 추가된다. 무료 DNS(DuckDNS 등)나 nip.io를 쓰면 $0.
> 가장 큰 절감 레버는 **미사용 시간 인스턴스 자동 중지**다(§4.2). 단, EBS는 중지 중에도 계속 과금된다.

---

## 5. 보안 그룹 (방화벽)

| 포트   | 프로토콜 | 소스                        | 용도                            |
|------|------|---------------------------|-------------------------------|
| 22   | TCP  | **본인 IP만** (`x.x.x.x/32`) | SSH 관리                        |
| 80   | TCP  | `0.0.0.0/0`               | HTTP (→443 리다이렉트, certbot 갱신) |
| 443  | TCP  | `0.0.0.0/0`               | HTTPS (API + WebSocket)       |
| 3306 | —    | **열지 않음**                 | MySQL은 루프백 전용                 |
| 6379 | —    | **열지 않음**                 | Redis는 루프백 전용                 |

> SSH(22)는 절대 `0.0.0.0/0`로 열지 않는다. 고정 IP가 없으면 AWS SSM Session Manager 사용을 고려한다.

---

## 6. 배포 절차

### 6.1 사전 준비

1. AWS 계정 + IAM 사용자(루트 직접 사용 금지)
2. EC2 키페어 생성
3. **S3 버킷 생성** + **EC2용 IAM Role(인스턴스 프로파일)** 생성 — 해당 버킷에 대한 최소 권한만 부여하고, 인스턴스 시작 시 이 Role을 연결한다(
   액세스 키 미사용, §3.5)
4. (선택) 도메인 또는 무료 서브도메인 확보

### 6.2 인스턴스 초기 셋업

```bash
# Ubuntu Server 24.04 LTS (arm64) 기준
sudo apt update && sudo apt -y upgrade
sudo apt -y install docker.io docker-compose-plugin git
sudo usermod -aG docker $USER   # 재로그인 필요

# (보험) 스왑 2GB 추가 — t4g.small(2GB)도 빌드/피크 대비 권장
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

### 6.3 환경변수(.env) 구성

프로젝트 루트의 `.env.example`을 기반으로 서버에 `.env` 작성 (절대 커밋 금지). prod 값 예시:

```dotenv
ACTIVE=prod

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

# --- S3 (객체 저장, 추후 연동 시) ---
# 자격증명은 EC2 IAM Role로 자동 주입되므로 Access Key를 넣지 않는다.
AWS_REGION=ap-northeast-2
S3_BUCKET=<버킷 이름>
```

> Compose 네트워크 내에서는 서비스명(`mysql`, `redis`)이 호스트명이 된다. 그래서 `DB_URL`/`REDIS_HOST`에 컨테이너 서비스명을 사용한다.
> S3 자격증명은 `.env`에 넣지 않는다 — IAM Role을 통해 AWS SDK가 자동으로 가져온다(§3.5). 단, **컨테이너에서 인스턴스 메타데이터(IMDS)에 접근
**할 수 있어야 하므로, Docker 실행 시 해당 경로가 막히지 않았는지 확인한다.

### 6.4 Docker Compose (예시 골격)

> 아래는 설계 참고용 골격이다. 실제 파일은 별도 PR로 추가한다.

```yaml
services:
  app:
    image: blursome:prod          # 또는 ghcr.io/<org>/blursome:tag
    env_file: .env
    depends_on: [ mysql, redis ]
    expose: [ "8080" ]              # 외부 노출 X, Nginx만 접근
    restart: unless-stopped
    # 메모리 제한 예시 (t4g.micro 1GB)
    environment:
      JAVA_TOOL_OPTIONS: "-Xms128m -Xmx384m"

  mysql:
    image: mysql:8.0
    command: [ "--innodb-buffer-pool-size=128M", "--skip-name-resolve" ]
    environment:
      MYSQL_DATABASE: blursome
      MYSQL_USER: ${DB_USERNAME}
      MYSQL_PASSWORD: ${DB_PASSWORD}
      MYSQL_ROOT_PASSWORD: ${DB_PASSWORD}
    ports: [ "127.0.0.1:3306:3306" ]   # 루프백 전용
    volumes: [ "mysql-data:/var/lib/mysql" ]
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    command: [ "redis-server", "--requirepass", "${REDIS_PASSWORD}",
               "--maxmemory", "96mb", "--maxmemory-policy", "noeviction",
               "--appendonly", "yes" ]
    ports: [ "127.0.0.1:6379:6379" ]   # 루프백 전용
    volumes: [ "redis-data:/data" ]
    restart: unless-stopped

  nginx:
    image: nginx:alpine
    depends_on: [ app ]
    ports: [ "80:80", "443:443" ]
    volumes:
      - ./nginx/conf.d:/etc/nginx/conf.d:ro
      - ./certbot/conf:/etc/letsencrypt:ro
    restart: unless-stopped

volumes:
  mysql-data:
  redis-data:
```

### 6.5 스키마 생성 — 임시 `ddl-auto: update` 방식 (확정)

prod는 기본 `ddl-auto: validate`(`application-prod.yml`)라 앱이 스키마를 만들지 않는다. **이번 배포는 마이그레이션 도구 없이, 최초
1회 `update`로 스키마를 자동 생성한 뒤 그대로 사용**한다.

절차:

1. 최초 기동 시에만 `ddl-auto`를 `update`로 적용한다.
    - 권장: `application-prod.yml`을 직접 고치지 말고 **환경변수로 오버라이드** —
      `.env`에 `SPRING_JPA_HIBERNATE_DDL_AUTO=update` 추가(Spring Relaxed Binding으로 yml 값을 덮어씀). 이러면 운영
      파일은 `validate`로 유지된다.
2. 앱이 떠서 테이블이 생성되면 정상 동작을 확인한다.
3. 이후 **`update` 오버라이드를 제거**(`.env`에서 줄 삭제)하고 재기동 → 다시 `validate`로 운영한다.

> ⚠️ 주의: `update`는 **컬럼/테이블 추가만** 반영하고 삭제·타입 변경 등은 안전하게 처리하지 못한다. 따라서 상시 `update` 운영은 지양하고, 스키마 변경이
> 잦아지면 추후 Flyway 도입을 검토한다(§9). DB 볼륨(`mysql-data`)이 영속화되어 있으면 한 번 만든 스키마는 재기동해도 유지된다.

### 6.6 빌드 & 배포 흐름 — GitHub Actions CI/CD (확정)

`.github/workflows/dev.yml`로 자동화한다. **`main` 브랜치 병합 시 자동 배포**된다. 상세 셋업은 [`GITHUB_DEPLOYMENT.md`](GITHUB_DEPLOYMENT.md) 참고.

```
PR → develop/main : test(Gradle) + container-check(compose 검증·이미지 빌드·ping 스모크)
push → develop     : test (통합 검증)
push → main        : test → arm64 이미지 빌드 → GHCR push → EC2 SSH 배포
```

배포 단계는 EC2에 SSH로 접속해 `git pull` → `docker compose pull` → `docker compose up -d`를 실행한다. **서버에서 Gradle 빌드를 하지 않으므로** t4g.small 메모리 부담이 없다.

필요한 GitHub Secrets: `EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY`, `DEPLOY_PATH`, `GHCR_PAT`·`GHCR_USERNAME`(한 쌍 — GHCR 패키지를 public으로 두면 둘 다 생략 가능). 상세는 `GITHUB_DEPLOYMENT.md §5`.

- 이미지: `ghcr.io/it-is-a-secret/blursome:latest` (+ 커밋 SHA 태그)
- `docker-compose.yml`의 `app`은 `image:`(GHCR)와 `build:`를 함께 가져, 서버는 pull로 / 로컬은 `--build`로 동일 태그를 쓴다.
- arm64 타깃은 buildx + QEMU로 빌드한다(에뮬레이션이라 다소 느림 — buildx GHA 캐시로 완화).

> **수동 배포(폴백)**: Actions 없이도 서버에서 `git pull && docker compose up -d --build`로 직접 빌드·기동할 수 있다. 단 t4g.small에서 빌드가 메모리를 점유하므로 스왑을 켜둔다.

### 6.7 HTTPS 발급

```bash
# certbot으로 최초 인증서 발급 (도메인 필요)
sudo certbot certonly --webroot -w /var/www/certbot -d <도메인>
# 자동 갱신은 cron/systemd timer로 등록
```

---

## 7. WebSocket(채팅) 프록시 설정

Nginx에 Upgrade 헤더 전달이 없으면 채팅 WebSocket이 끊긴다. 핵심 지시어:

```nginx
location /ws {              # 실제 STOMP/WebSocket 엔드포인트 경로에 맞춤
    proxy_pass http://app:8080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 3600s;
}
```

> 프론트엔드가 다른 오리진에서 동작하면 `app.cookie.same-site`를 `None`으로 조정해야 리프레시 토큰 쿠키가 동봉된다 (
`docs/ARCHITECTURE.md §8 운영 시 유의 사항` 참조).

---

## 8. 백업 / 운영 최소 수칙 (개발용)

| 항목       | 최소 방안                                                    |
|----------|----------------------------------------------------------|
| MySQL 백업 | `mysqldump`를 cron으로 1일 1회 → 로컬 파일(여유 시 S3 동기화)           |
| Redis 백업 | AOF 파일 유지로 충분(토큰은 재로그인 복구 가능)                            |
| 모니터링     | `docker logs`, `docker stats` + AWS 기본 CloudWatch(무료 한도) |
| 인스턴스 보호  | EBS는 중지 중에도 보존됨. 정기 스냅샷은 비용 발생하므로 선택                     |
| 로그 용량    | Docker 로그 로테이션(`max-size`, `max-file`) 설정으로 디스크 보호       |

---

## 9. 운영 전환 시 고려사항

개발이 끝나고 실서비스로 전환할 때 바꿀 것:

| 영역      | 개발용(현재)               | 운영용 전환                                        |
|---------|-----------------------|-----------------------------------------------|
| DB      | EC2 내부 MySQL          | **RDS(Multi-AZ)** — 자동 백업·장애 복구               |
| Redis   | EC2 내부 Redis          | **ElastiCache** — 또는 최소 별도 인스턴스               |
| 가용성     | 단일 EC2                | ALB + Auto Scaling Group(2 AZ 이상)             |
| 시크릿     | 서버 `.env` 파일          | **AWS Secrets Manager / SSM Parameter Store** |
| TLS     | EC2 내 Nginx + certbot | ALB + ACM 인증서                                 |
| 이미지 저장소 | GHCR                  | ECR                                           |

---

## 10. 아키텍처 결정 기록 (ADR)

### ADR-D01: 개발 기간 단일 EC2 올인원 배포

- 날짜: 2026-06-19
- 상태: 승인됨
- 결정: 개발/검증 기간 동안 RDS·ElastiCache·ALB 등 관리형 서비스를 사용하지 않고, 단일 EC2 인스턴스에 애플리케이션·MySQL·**Redis(EC2
  내부)**를 Docker Compose로 함께 배포한다.
- 이유: 실서비스가 아닌 개발 목적이며 비용 최소화가 최우선이다. 관리형 서비스(RDS·ElastiCache·ALB)는 가용성·운영 편의를 제공하지만 개발 단계에서는 과한
  고정비다. 본 계정은 프리티어가 없으므로, 단일 인스턴스 + 내부 데이터 계층으로 월 비용을 약 $5~21 범위(가동 방식에 따라, §4 참조)로 묶는다.
- 결과:
    - 고가용성/자동 백업 없음 — 인스턴스 장애 시 수동 복구. 개발용으로 수용 가능
    - MySQL/Redis는 루프백 바인딩으로 외부 비노출, Nginx(443)만 공개
    - 운영 전환 시 §9 체크리스트에 따라 데이터 계층을 관리형으로 이전
    - prod 프로파일의 `secure=true` 쿠키·WebSocket 때문에 Nginx + Let's Encrypt HTTPS 종료가 필수 구성

### ADR-D02: 프리티어 없는 종량제 기준 비용 최적화

- 날짜: 2026-06-19
- 상태: 승인됨
- 결정: 본 계정은 프리티어가 만료되었으므로, x86(t3) 대신 **ARM Graviton(t4g) 인스턴스**를 기본으로 한다. 기본 권장은 t4g.small(2GB),
  최저가 옵션은 t4g.micro(1GB) + 2GB 스왑 + JVM/MySQL 메모리 상한이다. 추가로 미사용 시간 인스턴스 자동 중지, EBS 20GB 축소, gp3 사용으로
  절대 비용을 최소화한다.
- 이유: 동일 성능에서 t4g가 t3보다 약 20% 저렴하다. 프리티어가 없으므로 모든 항목이 실비이며, 컴퓨팅·스토리지·IPv4를 각각 최소화해야 한다. 특히 2024년부터
  공인 IPv4가 유료화되어 상시 가동 시 무시할 수 없는 고정비가 된다.
- 결과:
    - Docker 이미지를 `arm64`로 빌드해야 한다(§6.6).
    - 메모리가 빠듯한 t4g.micro에서는 서버 내 Gradle 빌드를 피하고 CI 빌드 이미지를 pull한다(§6.6).
    - 개발 시간대 외 자동 중지 시 자동 할당 공인 IP를 쓰면 IPv4 idle 요금을 피할 수 있으나 IP가 바뀌므로 DNS/카카오 redirect 갱신이 필요하다(
      §4.2).

### ADR-D03: 인스턴스/OS/스키마/객체저장 확정 사항

- 날짜: 2026-06-19
- 상태: 승인됨
- 결정:
    1. 인스턴스 유형은 **t4g.small(ARM 2GB)** 로 확정한다.
    2. OS 이미지는 **Ubuntu Server 24.04 LTS (HVM), SSD Volume Type** (표준판, Pro 아님) 로 확정한다.
    3. DB 스키마는 마이그레이션 도구 없이 **최초 1회 `ddl-auto: update`로 자동 생성** 후 `validate`로 되돌려 사용한다.
    4. 파일/이미지 등 객체 저장은 **별도 S3 버킷**을 사용하고, EC2에는 **IAM Role**을 부여해 액세스 키 없이 접근한다.
- 이유:
    - t4g.small은 JVM+MySQL+Redis 동시 구동에 메모리 여유가 있어 개발 안정성과 비용의 균형점이다.
    - 개발 초기에는 엔티티 변경이 잦으므로 `update` 자동 생성이 가장 빠르다. 다만 한계가 있어 임시 방편으로만 쓴다.
    - S3 분리로 EBS 용량·비용을 절약하고 객체 내구성을 확보한다. IAM Role은 자격증명 노출 위험을 제거한다.
- 결과:
    - 비용 최소화를 위해 시간당 구독료가 붙는 Ubuntu Pro 대신 **표준 Ubuntu Server**를 선택했다(Pro 구독료 $0). 개발용으로 Pro의
      ESM·Livepatch는 불필요하다.
    - `update`는 컬럼 추가만 안전하므로 상시 사용 금지. 스키마 변경이 잦아지면 Flyway 도입 검토(§6.5, §9).
    - S3 연동 코드는 아직 없음 — 도입 시 AWS SDK(`software.amazon.awssdk:s3`) 의존성 추가 필요(§3.5).

```