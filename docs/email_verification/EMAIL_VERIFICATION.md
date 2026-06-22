# 이메일 인증 발송 — Gmail SMTP 구현 설계

학교 이메일 인증 코드의 **실제 발송부**를 Gmail SMTP로 구현한 작업의 설계 문서입니다.
온보딩 인증 플로우 전반은 [`docs/member/MEMBER_ONBOARDING.md`](./member/MEMBER_ONBOARDING.md)를 참조합니다.

---

## 1. 배경

회원 온보딩의 학교 이메일 인증 흐름(코드 발송 → 검증 → 프로필 작성)은 [회원 온보딩 이슈](./goodjunseon/issue-member-onboarding.md)에서 이미 구현되었으나, **실제 메일 발송부는 로깅 스텁(`LoggingEmailVerificationSender`)** 으로 남아 있었습니다. 스텁은 인증 코드를 로그로만 출력하므로 실사용자가 코드를 수신할 수 없습니다.

본 작업은 발송 포트(`EmailVerificationSender`)의 **실제 구현체를 Gmail SMTP(`JavaMailSender`) 기반으로 추가**하여 온보딩을 end-to-end로 동작시킵니다. 도메인·서비스·Redis 저장·코드 생성 로직은 변경하지 않고, 발송 어댑터만 추가·교체합니다.

---

## 2. 설계 결정

| 항목 | 결정 | 근거 |
|---|---|---|
| 전송 방식 | Gmail SMTP (`spring-boot-starter-mail`, `smtp.gmail.com:587`, STARTTLS) | 설정이 단순하고 기존 인프라와 정합. 앱 비밀번호로 인증 |
| 발송 동기성 | 동기 발송 유지 | 기존 호출 흐름이 동기. 발송 지연·실패가 API 응답에 직접 반영 |
| 실 발송 빈 | `SmtpEmailVerificationSender`를 `@Profile("!maillog")`로 등록(기본값) | 로컬 포함 모든 환경에서 실제 발송 |
| 스텁 처리 | `LoggingEmailVerificationSender`를 `@Profile("maillog")` opt-in으로 한정 | 자격증명 없이 로그로만 점검할 때만 활성화 |
| 관심사 분리 | 포트 / 전송 어댑터 / 콘텐츠 생성 / 설정 4계층 분리 | 추후 다른 발송 수단으로 무중단 교체 |
| 발송 실패 처리 | `MEMBER_EMAIL_SEND_FAILED`(502)로 변환 | 일관된 에러 응답(`GlobalExceptionHandler`) |
| 본문 형식 | plain text (UTF-8) | 1차 단순 구현. HTML 필요 시 콘텐츠 팩토리만 변경 |

---

## 3. 계층 분리 구조

```
[도메인/서비스]  MemberOnboardingService
       │ depends on
       ▼
[포트]          EmailVerificationSender              ← 기존, 변경 없음
       ▲ implements
       ├── SmtpEmailVerificationSender     @Profile("!maillog") (SMTP 전송 어댑터, 기본)
       └── LoggingEmailVerificationSender  @Profile("maillog")  (로깅 스텁, opt-in)
                │ uses
                ▼
[콘텐츠 생성]   VerificationEmailFactory             ← 신규, 전송수단 무관(제목/본문)
[설정]          MailProperties(@ConfigurationProperties)  ← 신규(발신자 표기)
```

**핵심**: SMTP/`JavaMailSender` 의존은 `SmtpEmailVerificationSender` 한 곳에만 격리됩니다. 향후 Gmail REST API·AWS SES 등 새 발송 수단을 추가할 때, **포트·콘텐츠 팩토리·설정·서비스는 그대로 두고 전송 어댑터 클래스 하나만 추가**하면 됩니다.

---

## 4. 컴포넌트

위치: `com.blursome.blursome.member.verification`

| 클래스 | 역할 |
|---|---|
| `EmailVerificationSender` (기존) | 발송 포트. `send(String email, String code)` |
| `SmtpEmailVerificationSender` (신규) | Gmail SMTP 전송 어댑터. `MimeMessage`(UTF-8) 구성 후 `JavaMailSender.send()` 호출, 실패 시 에러코드 변환 |
| `VerificationEmailFactory` (신규) | 전송수단 무관 제목/본문 생성 → `VerificationEmailContent(subject, body)` |
| `MailProperties` (신규) | `@ConfigurationProperties("app.mail")` — 발신자 표기(`from`) 바인딩 |
| `LoggingEmailVerificationSender` (기존) | `@Profile("maillog")` opt-in. 코드를 로그로만 출력 |

관련 에러코드: `MemberErrorCode.MEMBER_EMAIL_SEND_FAILED`(`HttpStatus.BAD_GATEWAY`).

---

## 5. 설정

SMTP 접속 정보는 Spring Boot가 `spring.mail.*`로 `JavaMailSender`를 자동 구성합니다. 발신자 표기는 도메인 관점에서 `app.mail.from`으로 분리해 `MailProperties`가 바인딩합니다(`@ConfigurationPropertiesScan`이 등록).

```yaml
# application-prod.yml
spring:
  mail:
    host: smtp.gmail.com
    port: 587
    username: ${GMAIL_ADDRESS}
    password: ${GMAIL_APP_PASSWORD}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
app:
  mail:
    from: ${GMAIL_ADDRESS}
```

| 환경변수 | 설명 |
|---|---|
| `GMAIL_ADDRESS` | 발신 Gmail 주소 |
| `GMAIL_APP_PASSWORD` | Google 계정 **앱 비밀번호** (2단계 인증 활성화 후 발급. 일반 비밀번호로는 SMTP 인증 불가) |

로컬을 포함한 모든 환경이 기본으로 실제 SMTP를 사용하므로, 로컬에서도 `.env`에 `GMAIL_ADDRESS`·`GMAIL_APP_PASSWORD`를 채워야 발송이 성공합니다(`application-local.yml`은 미설정 시 빈 자격증명으로 빈만 생성). 발송 없이 코드만 확인하려면 `maillog` 프로파일을 함께 활성화(예: `ACTIVE=local,maillog`)해 스텁(`[EMAIL-STUB]` 로그)으로 대체합니다.

---

## 6. 발송 흐름

```
MemberOnboardingService.sendSchoolEmailVerificationCode(memberId, schoolEmail)
  ├─ 도메인 정책 검증(@gs.anyang.ac.kr) · 이미 인증 여부 확인
  ├─ VerificationCodeGenerator.generate()        → 6자리 코드
  ├─ SchoolEmailVerificationStore.save(...)       → Redis 저장(TTL 5분)
  └─ EmailVerificationSender.send(email, code)
        └─ (기본/!maillog) SmtpEmailVerificationSender
             ├─ VerificationEmailFactory.build(code)  → 제목/본문
             ├─ MimeMessageHelper로 from·to·subject·text 구성(UTF-8)
             ├─ JavaMailSender.send(message)
             └─ MailException/MessagingException → BaseException(MEMBER_EMAIL_SEND_FAILED)
```

만료 시간 안내(5분)는 단일 출처 `SchoolEmailVerificationStore.CODE_TTL`을 저장 TTL과 공유합니다.

---

## 7. 확장 방법 (다른 발송 수단으로 교체)

1. `EmailVerificationSender`를 구현하는 새 어댑터 작성 (예: `GmailApiEmailVerificationSender`).
2. 메일 문구는 `VerificationEmailFactory`를 **그대로 주입·재사용**.
3. 프로파일 또는 `@ConditionalOnProperty`로 활성 어댑터를 분기 — 동시에 둘 이상 뜨지 않도록.
4. 포트·서비스·콘텐츠 팩토리·`MailProperties`는 **변경 없음**.

---

## 8. 테스트

| 테스트 | 검증 내용 |
|---|---|
| `VerificationEmailFactoryTest` | 제목/본문에 코드·만료시간 문구 포함 (전송수단 무관 단위) |
| `SmtpEmailVerificationSenderTest` | `JavaMailSender` mock — 올바른 수신자/제목/코드로 1회 발송 / 발송 실패 시 `MEMBER_EMAIL_SEND_FAILED` 변환 |

테스트 컨벤션은 `AuthServiceTest`(`@ExtendWith(MockitoExtension.class)`, AssertJ, given-when-then, 한글 `@DisplayName`)를 따릅니다.

---

## 9. 로컬에서 실제 발송 테스트

로컬 기본 프로파일(`local`)이 실제 Gmail SMTP를 사용하므로, 로컬에서 실제 인증 메일이 발송되는지 end-to-end로 확인할 수 있습니다.

### 사전 준비

- **MySQL · Redis 기동** (회원 생성·코드 저장에 필요)
- **`.env` 설정**: `GMAIL_ADDRESS`, `GMAIL_APP_PASSWORD`(앱 비밀번호, 공백 없이) + 카카오 OAuth 키
- **수신 가능한 안양대 이메일**: 정책상 수신지는 `@gs.anyang.ac.kr`만 허용되므로, **본인이 받아볼 수 있는 `@gs.anyang.ac.kr` 주소**가 필요합니다.

### 절차

**1) 앱 실행** (기본 `ACTIVE=local` → 실제 SMTP 발송)

```bash
./gradlew bootRun
```

**2) JWT 액세스 토큰 발급** — 온보딩 API는 인증이 필요합니다(`@AuthenticationPrincipal`).

브라우저에서 아래 주소로 접속 → 카카오 로그인·동의 → 콜백 JSON의 `accessToken`을 복사합니다.

```
http://localhost:8080/api/auth/oauth/kakao/authorize
```

```jsonc
// 콜백 응답 예시
{ "data": { "accessToken": "eyJhbGci...", "tokenType": "Bearer", "expiresIn": 1800 } }
```

**3) 인증 코드 발송** — 실제로 메일이 나갑니다.

```bash
curl -i -X POST http://localhost:8080/api/members/me/school-email/verification-codes \
  -H "Authorization: Bearer <ACCESS_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"schoolEmail":"본인아이디@gs.anyang.ac.kr"}'
```

- **성공**: `204 No Content`. 잠시 후 수신함에 **`[BlurSome] 학교 이메일 인증 코드`** 메일 도착(6자리 코드).
- **실패**: `502` + `MEMBER_502_EMAIL_SEND_FAILED`. 콘솔에 `인증 메일 발송 실패 ...` 로그 → 아래 문제 해결 참고.

**4) 인증 코드 검증** — 메일로 받은 6자리 코드를 입력합니다(발송 후 5분 이내).

```bash
curl -i -X POST http://localhost:8080/api/members/me/school-email/verifications \
  -H "Authorization: Bearer <ACCESS_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"schoolEmail":"본인아이디@gs.anyang.ac.kr","code":"123456"}'
```

- **성공**: `204 No Content` → 가입 단계가 `UNVERIFIED → VERIFIED`로 전진.

**5) 결과 확인**

```bash
curl -s http://localhost:8080/api/members/me -H "Authorization: Bearer <ACCESS_TOKEN>"
# → registrationStatus 가 "VERIFIED" 인지 확인
```

> Swagger UI(`http://localhost:8080/swagger-ui.html`)에서 **Authorize**에 토큰을 넣고 같은 순서로 호출해도 됩니다.

### 문제 해결

| 증상 | 원인 / 조치 |
|---|---|
| `502` + 발송 실패 로그 (`AuthenticationFailedException`) | 앱 비밀번호 오타·공백, 2단계 인증 미설정, `GMAIL_ADDRESS`와 비밀번호 발급 계정 불일치 |
| `502` + 연결 타임아웃 | 사내/학교 네트워크가 587 포트 차단 — 다른 망에서 시도 |
| 메일이 안 옴 | 스팸함 확인, 수신 주소 오타, `GMAIL_ADDRESS` 미설정(빈 자격증명) |
| `400` 도메인 거부 | 수신지가 `@gs.anyang.ac.kr`가 아님(`SchoolEmailPolicy`) |
| **실제 발송 없이 코드만 확인하고 싶을 때** | `ACTIVE=local,maillog`로 기동 → 콘솔 `[EMAIL-STUB]` 로그에 코드 출력(SMTP 미사용) |

---

## 10. 비포함 범위

- 비동기(`@Async`) 발송 / 발송 레이트리밋 / 재시도
- 인증 시도 횟수 제한
- HTML 메일 템플릿
- Gmail REST API·SES 등 대체 어댑터 (확장 지점만 마련)
