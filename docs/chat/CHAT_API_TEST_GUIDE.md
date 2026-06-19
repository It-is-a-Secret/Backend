# 채팅 기능 Postman 테스트 가이드

BlurSome 채팅 기능(REST 조회·단계 동의·나가기 + WebSocket/STOMP 실시간 송수신)을 **Postman**으로 점검하기 위한
시나리오·실행 방법 문서입니다. 기능 설계는 [`CHAT_FEATURE.md`](./CHAT_FEATURE.md), 인증 흐름은
[`../ARCHITECTURE.md § 8`](../ARCHITECTURE.md)를 따릅니다.

> 대상 독자: 로컬에서 채팅 API를 손으로 검증하려는 백엔드/QA. Postman 외에 MySQL(or MariaDB) 클라이언트가 필요합니다.

---

## 0. 한눈에 보기

| 단계 | 내용 |
|----|----|
| ① 서버 실행 | `local` 프로파일, `http://localhost:8080` |
| ② 데이터 시드 | 회원 2명 + ACTIVE 방 1개 + 참여행 2개 (방 개설 REST가 없어 DB로 준비) |
| ③ 토큰 발급 | Postman Pre-request 스크립트로 AccessToken 직접 생성(로컬) 또는 카카오 콜백 |
| ④ REST 테스트 | 방 목록/단건/이력 조회, 단계 동의, 나가기 |
| ⑤ STOMP 테스트 | `/ws` 연결 → 구독 → 송신/읽음 수신, 단계 변경 브로드캐스트, 에러 케이스 |

### 테스트 대상 API 요약

**REST** — base path `/api/chat/rooms`, 모두 `Authorization: Bearer <AT>` 필요

| 메서드 | 경로 | 설명 |
|----|----|----|
| GET | `/api/chat/rooms` | 내 채팅방 목록(안읽음 수 포함) |
| GET | `/api/chat/rooms/{roomId}` | 방 단건 조회 |
| GET | `/api/chat/rooms/{roomId}/messages?cursor=&size=` | 메시지 이력(id 커서, 최신순) |
| POST | `/api/chat/rooms/{roomId}/progress/agree` | 다음 단계 공개 동의 |
| POST | `/api/chat/rooms/{roomId}/leave` | 채팅방 나가기(종료) |

**STOMP** — 핸드셰이크 `GET /ws`, 인증은 CONNECT 프레임의 `Authorization` 헤더

| 방향 | Destination | 설명 |
|----|----|----|
| 구독 | `/topic/rooms/{roomId}` | 메시지·단계 변경 브로드캐스트 수신(참여자만) |
| 구독 | `/user/queue/errors` | 내 개인 오류 통지 수신 |
| 송신 | `/app/rooms/{roomId}/send` | 메시지 전송 `{ "type": "TEXT", "content": "..." }` |
| 송신 | `/app/rooms/{roomId}/read` | 읽음 위치 갱신 `{ "lastReadMessageId": 10 }` |

> ⚠️ **방 개설은 REST로 노출되지 않습니다.** 방은 매칭 도메인이 `ChatRoomService.openRoom`으로 만들기 때문에,
> 테스트용 방은 아래 ②에서 DB에 직접 시드합니다.

---

## 1. 사전 준비

### 1-1. 서버 실행 (local)

`local` 프로파일로 실행합니다. 필요한 환경 변수(요약):

| 변수 | 예시/설명 |
|----|----|
| `SPRING_PROFILES_ACTIVE` | `local` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | MySQL/MariaDB 접속 정보 |
| `REDIS_HOST` / `REDIS_PORT` | 기본 `localhost:6379` |
| `JWT_SECRET` | HS256 서명 키(**32바이트 이상**). 토큰 직접 생성 시 동일 값을 Postman에도 넣습니다 |
| `JWT_ACCESS_TOKEN_EXPIRES_IN` | 기본 `1800`(초) |
| `KAKAO_CLIENT_ID` / `KAKAO_CLIENT_SECRET` / `KAKAO_REDIRECT_URI` | 카카오 로그인으로 토큰을 받을 때만 필요 |

```bash
# 예시 (Git Bash)
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

- 포트: `8080`, `ddl-auto: update`(local)라 첫 실행 시 채팅 테이블이 자동 생성됩니다.
- 정상 기동 확인: Swagger `http://localhost:8080/swagger-ui.html`.

### 1-2. 테스트 데이터 시드 (DB)

회원 2명(1001=A, 1002=B), 두 회원의 **ACTIVE** 방 1개(roomId=1), 참여행 2개, 예시 메시지 2개를 넣습니다.
`active_pair_key`는 두 회원 id를 오름차순 결합한 `1001-1002` 형식이어야 합니다(ACTIVE 방 중복 방지 유니크 키).

```sql
-- 회원 2명 (NOT NULL: provider, provider_id, name, role, activity_status, registration_status, created_at, updated_at)
INSERT INTO member (id, provider, provider_id, name, email, role, activity_status, registration_status, created_at, updated_at)
VALUES
  (1001, 'KAKAO', 'test-a', '테스트A', 'a@test.com', 'USER', 'ACTIVE', 'COMPLETED', NOW(), NOW()),
  (1002, 'KAKAO', 'test-b', '테스트B', 'b@test.com', 'USER', 'ACTIVE', 'COMPLETED', NOW(), NOW());

-- ACTIVE 방 1개
INSERT INTO chat_room (id, room_status, progress_status, last_message_id, active_pair_key, created_at, updated_at)
VALUES (1, 'ACTIVE', 'MATCHED', NULL, '1001-1002', NOW(), NOW());

-- 참여행 2개 (leftAt = NULL, 동의 단계 MATCHED)
INSERT INTO chat_room_member (id, chat_room_id, member_id, joined_at, left_at, last_read_message_id, agreed_progress_status, created_at, updated_at)
VALUES
  (1, 1, 1001, NOW(), NULL, NULL, 'MATCHED', NOW(), NOW()),
  (2, 1, 1002, NOW(), NULL, NULL, 'MATCHED', NOW(), NOW());

-- 예시 메시지 2개 + 미리보기 갱신
INSERT INTO chat_message (id, chat_room_id, sender_id, content, type, created_at, updated_at)
VALUES
  (1, 1, 1001, '안녕하세요 반가워요', 'TEXT', NOW(), NOW()),
  (2, 1, 1002, '네 반갑습니다',     'TEXT', NOW(), NOW());
UPDATE chat_room SET last_message_id = 2 WHERE id = 1;
```

> 테스트를 처음부터 다시 하려면 역순으로 비우세요: `chat_message` → `chat_room_member` → `chat_room` → `member`.

### 1-3. Postman 환경(Environment) 변수

새 Environment를 만들고 아래 변수를 등록합니다.

| 변수 | 값(예시) | 설명 |
|----|----|----|
| `baseUrl` | `http://localhost:8080` | REST 호스트 |
| `wsUrl` | `ws://localhost:8080/ws` | STOMP 핸드셰이크 |
| `jwtSecret` | `(서버의 JWT_SECRET과 동일)` | 토큰 직접 생성용 |
| `memberAId` | `1001` | 사용자 A |
| `memberBId` | `1002` | 사용자 B |
| `roomId` | `1` | 시드한 방 |
| `accessTokenA` | (자동 채움) | A의 AccessToken |
| `accessTokenB` | (자동 채움) | B의 AccessToken |

### 1-4. AccessToken 발급

#### 방법 A — Postman Pre-request 스크립트로 직접 생성 (로컬 권장)

인증 필터는 **무상태**라 토큰만 유효하면 통과하고, 실제 회원·참여 검증은 DB(②에서 시드)로 합니다. 따라서 로컬에서는
서버와 동일한 `jwtSecret`으로 토큰을 만들어 쓰는 게 가장 빠릅니다.

**Collection → Pre-request Script**에 아래를 붙여 넣으면 모든 요청 전에 A/B 토큰이 자동 갱신됩니다.
(Postman 내장 `CryptoJS` 사용, HS256)

```javascript
function b64url(wordArray) {
  return CryptoJS.enc.Base64.stringify(wordArray)
    .replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}
function issueAccessToken(memberId) {
  const secret  = pm.environment.get('jwtSecret');
  const now     = Math.floor(Date.now() / 1000);
  const header  = { alg: 'HS256', typ: 'JWT' };
  // 서버 JwtTokenProvider 규약: sub=memberId, 페이로드 claim "typ"="access", role
  const payload = { sub: String(memberId), typ: 'access', role: 'USER', iat: now, exp: now + 1800 };
  const h   = b64url(CryptoJS.enc.Utf8.parse(JSON.stringify(header)));
  const p   = b64url(CryptoJS.enc.Utf8.parse(JSON.stringify(payload)));
  const sig = b64url(CryptoJS.HmacSHA256(h + '.' + p, secret));
  return `${h}.${p}.${sig}`;
}
pm.environment.set('accessTokenA', issueAccessToken(pm.environment.get('memberAId')));
pm.environment.set('accessTokenB', issueAccessToken(pm.environment.get('memberBId')));
```

> 주의: `jwtSecret`은 서버 값과 **정확히 동일**해야 하며 32바이트 이상이어야 합니다(HS256 최소 키 길이). 다르면 `JWT_401_INVALID`.

#### 방법 B — 카카오 콜백으로 발급 (실제 흐름)

1. 브라우저로 `GET {{baseUrl}}/api/auth/oauth/kakao/authorize` 진입 → 카카오 로그인/동의.
2. 콜백 `GET /api/auth/oauth/kakao/callback?code=...` 응답 바디에서 `data.accessToken`을 복사.
3. 그 회원이 **시드한 방의 참여자**여야 채팅 API가 통과합니다(아니면 시드의 `member_id`를 실제 회원 id로 맞추세요).

응답 형태:

```json
{
  "timestamp": "2026-06-19T12:00:00.000+09:00",
  "data": { "accessToken": "eyJhbGciOi...", "tokenType": "Bearer", "expiresIn": 1800 }
}
```

---

## 2. REST 테스트 시나리오

각 요청 헤더에 `Authorization: Bearer {{accessTokenA}}`(또는 B)를 넣습니다. 성공 응답은 `{ timestamp, data }`,
오류 응답은 `{ timestamp, status, message, code }` 형태입니다.

### 시나리오 R-1. 방 목록 조회

- **요청**: `GET {{baseUrl}}/api/chat/rooms`  — Header `Authorization: Bearer {{accessTokenA}}`
- **기대**: `200`, 시드한 방이 안읽음 수와 함께 반환. A 기준 안읽음 = B가 보낸 메시지(id=2) 중 미열람 → `1`.

```json
{
  "timestamp": "2026-06-19T12:00:00.000+09:00",
  "data": [
    { "roomId": 1, "roomStatus": "ACTIVE", "progressStatus": "MATCHED", "lastMessageId": 2, "unreadCount": 1 }
  ]
}
```

### 시나리오 R-2. 방 단건 조회

- **요청**: `GET {{baseUrl}}/api/chat/rooms/{{roomId}}` — A 토큰
- **기대**: `200`, 위 항목 단건.
- **변형(권한)**: 참여자가 아닌 토큰으로 호출 → `403 NOT_PARTICIPANT`(`CHAT_403_NOT_PARTICIPANT`).
- **변형(없음)**: 존재하지 않는 `roomId`(예: 999) → `404 ROOM_NOT_FOUND`.

### 시나리오 R-3. 메시지 이력 조회 (커서 페이지네이션)

- **요청**: `GET {{baseUrl}}/api/chat/rooms/{{roomId}}/messages?size=30` — A 토큰
- **기대**: `200`, 최신순(`id` 내림차순) 메시지 배열.

```json
{
  "timestamp": "2026-06-19T12:00:00.000+09:00",
  "data": [
    { "messageId": 2, "roomId": 1, "senderId": 1002, "type": "TEXT", "content": "네 반갑습니다",     "createdAt": "2026-06-19T11:59:00" },
    { "messageId": 1, "roomId": 1, "senderId": 1001, "type": "TEXT", "content": "안녕하세요 반가워요", "createdAt": "2026-06-19T11:58:00" }
  ]
}
```

- **다음 페이지**: 가장 오래된 `messageId`를 `cursor`로 전달 → `...?cursor=1&size=30` (그 id보다 과거만 조회).
- `size`는 `1~100`으로 보정됩니다.

### 시나리오 R-4. 단계 동의 (상호 동의 → 단계 상승)

상호 동의 모델이므로 **양쪽이 모두 동의**해야 방 단계가 오릅니다.

1. **A 동의**: `POST {{baseUrl}}/api/chat/rooms/{{roomId}}/progress/agree` — A 토큰
   - 기대 `200`, 방은 아직 `MATCHED`(A만 동의, 대기). 응답 `progressStatus: "MATCHED"`.
2. **B 동의**: 같은 요청 — B 토큰
   - 기대 `200`, 방 단계 상승 → `progressStatus: "PHOTO_REVEAL_STEP_1"`.
   - 이때 STOMP 구독자에게 단계 변경이 브로드캐스트됩니다(시나리오 W-5에서 확인).
3. **재동의(이미 동의)**: A가 같은 단계에 다시 동의 → `409 PROGRESS_ALREADY_AGREED`.

```json
// 2단계(B 동의) 성공 응답
{ "timestamp": "...", "data": { "roomId": 1, "roomStatus": "ACTIVE", "progressStatus": "PHOTO_REVEAL_STEP_1", "lastMessageId": 2, "unreadCount": 0 } }
```

### 시나리오 R-5. 채팅방 나가기 (종료)

1:1이라 한쪽이 나가면 방이 즉시 `CLOSED`되고 재입장이 불가합니다.

1. **나가기**: `POST {{baseUrl}}/api/chat/rooms/{{roomId}}/leave` — A 토큰 → `204 No Content`.
2. **나간 뒤 조회**: `GET /api/chat/rooms/{{roomId}}` — A 토큰 → `404 ROOM_NOT_FOUND`(종료된 방은 숨김).
3. **상대도 차단**: B 토큰으로 조회 → `404 ROOM_NOT_FOUND`.

> ⚠️ 이 시나리오는 방을 종료시킵니다. STOMP 테스트(아래)를 먼저 끝내거나, 끝나면 ②의 시드를 재실행하세요.

### REST 오류 케이스 정리

| 상황 | HTTP | code |
|----|----|----|
| 비참여자 접근 | 403 | `CHAT_403_NOT_PARTICIPANT` |
| 방 없음 / 종료된 방 조회 | 404 | `CHAT_404_ROOM_NOT_FOUND` |
| 이미 동의한 단계 재동의 / 마지막 단계 | 409 | `CHAT_409_PROGRESS_ALREADY_AGREED` |
| 토큰 없음/만료/위조 | 401 | `JWT_401_UNAUTHORIZED` / `JWT_401_EXPIRED` / `JWT_401_INVALID` |

---

## 3. WebSocket / STOMP 테스트 시나리오

채팅 실시간 채널은 **STOMP over WebSocket**입니다. 두 사용자(A·B)를 각각 **WebSocket 요청 탭**으로 띄워
A가 보낸 메시지를 B가 수신하는지 확인합니다.

> ℹ️ **NULL 종료자 주의** — STOMP는 각 프레임 끝에 **NULL 문자(`0000`)**가 와야 합니다. 아래 프레임 표기에서
> `^@`는 이 NULL 1바이트를 뜻합니다. Postman WebSocket 메시지 입력창에서 NULL 입력이 까다로운 환경이라면
> **부록 A의 StompJS 스니펫**으로 대체하세요(프레이밍·하트비트를 자동 처리해 가장 안정적입니다).

### 3-1. 연결 (CONNECT)

1. Postman → **New → WebSocket Request**.
2. URL에 `{{wsUrl}}` (= `ws://localhost:8080/ws`) 입력 → **Connect**. (핸드셰이크 `/ws`는 SecurityConfig에서 permit, 인증은 CONNECT에서)
3. 연결되면 메시지 타입을 **Raw/Text**로 두고 아래 CONNECT 프레임을 전송:

```
CONNECT
accept-version:1.2
host:localhost
Authorization:Bearer {{accessTokenA}}

^@
```

- 성공 시 서버가 `CONNECTED` 프레임을 응답합니다.
- **인증 실패**(헤더 없음/만료/위조): 서버가 `ERROR` 프레임을 보내고 연결을 종료합니다.

> Postman 변수(`{{accessTokenA}}`)는 WebSocket 메시지 본문에서 치환되지 않을 수 있습니다. 그럴 땐 Pre-request로
> 생성한 토큰 값을 Environment에서 복사해 직접 붙여 넣으세요(부록 A 스니펫을 쓰면 자동 처리됩니다).

### 3-2. 구독 (SUBSCRIBE)

개인 오류 큐와 방 토픽을 구독합니다(서로 다른 `id` 사용).

```
SUBSCRIBE
id:sub-errors
destination:/user/queue/errors

^@
```

```
SUBSCRIBE
id:sub-room-1
destination:/topic/rooms/1

^@
```

- A와 B 두 세션 모두 `/topic/rooms/1`을 구독합니다.
- **비참여자 구독 차단 확인**: 시드 방에 속하지 않은 회원 토큰으로 `/topic/rooms/1`을 구독하면, 연결은 유지된 채
  `/user/queue/errors`로 `CHAT_403_NOT_PARTICIPANT` 통지가 오고 구독은 성립하지 않습니다.

### 3-3. 메시지 송신/수신 (SEND → 브로드캐스트)

A 세션에서 전송:

```
SEND
destination:/app/rooms/1/send
content-type:application/json

{"type":"TEXT","content":"안녕하세요 STOMP"}^@
```

- **기대**: A·B 두 세션의 `/topic/rooms/1` 구독으로 동일한 `MESSAGE` 프레임 수신(발신자 본인도 받음).

```json
// /topic/rooms/1 수신 본문
{ "messageId": 3, "roomId": 1, "senderId": 1001, "type": "TEXT", "content": "안녕하세요 STOMP", "createdAt": "2026-06-19T12:01:00" }
```

> 발신자(`senderId`)는 본문이 아니라 CONNECT principal에서 결정됩니다. 본문에 `senderId`를 넣어도 무시됩니다(위조 방지).

### 3-4. 읽음 처리 (read)

B 세션에서 방금 받은 메시지까지 읽음 처리:

```
SEND
destination:/app/rooms/1/read
content-type:application/json

{"lastReadMessageId":3}^@
```

- **기대**: 정상 처리(별도 브로드캐스트 없음). 이후 B로 `GET /api/chat/rooms/1` 호출 시 `unreadCount`가 0으로 줄어듦.
- **커서 조작 차단 확인**: `{"lastReadMessageId": 999999999}`처럼 그 방에 없는 id를 보내면 `/user/queue/errors`로
  `CHAT_400_INVALID_MESSAGE` 통지가 오고 읽음 위치는 바뀌지 않습니다.

### 3-5. 단계 변경 브로드캐스트 (REST 동의 → STOMP 푸시)

A·B가 `/topic/rooms/1`을 구독한 상태에서 시나리오 **R-4**(양쪽 단계 동의)를 수행하면, 단계 상승 시 같은 토픽으로
단계 변경 이벤트가 푸시됩니다. 메시지 응답과는 `eventType`으로 구분합니다.

```json
// /topic/rooms/1 수신 본문 (B가 동의해 단계가 오른 직후)
{ "eventType": "PROGRESS_CHANGED", "roomId": 1, "progressStatus": "PHOTO_REVEAL_STEP_1" }
```

### 3-6. STOMP 오류 케이스

| 상황 | 결과 |
|----|----|
| CONNECT에 토큰 없음/만료/위조 | `ERROR` 프레임 + 연결 종료 |
| 비참여자가 `/topic/rooms/{id}` 구독 | `/user/queue/errors`로 `CHAT_403_NOT_PARTICIPANT`, 구독 차단(연결 유지) |
| 종료(CLOSED)된 방에 `send`/`read` | `/user/queue/errors`로 `CHAT_409_ROOM_CLOSED` |
| 빈 본문/`SYSTEM` 타입/잘못된 JSON·enum | `/user/queue/errors`로 `CHAT_400_INVALID_MESSAGE` |
| 조작된 읽음 커서(방에 없는 id) | `/user/queue/errors`로 `CHAT_400_INVALID_MESSAGE` |

> 오류 통지는 **해당 세션에만** 전달됩니다(같은 계정의 다른 세션으로 새지 않음).

---

## 4. 통합(E2E) 시나리오 — 추천 순서

1. ② 시드 실행 → ③ 토큰 준비(A·B).
2. **R-1/R-2/R-3** 조회로 초기 상태 확인.
3. WebSocket 탭 2개로 **A·B 연결(3-1) → 구독(3-2)**.
4. **A 송신(3-3)** → B 수신 확인 → **B 읽음(3-4)**.
5. **R-4** 단계 동의(A→B) 진행하며 **3-5** 단계 브로드캐스트 수신 확인.
6. 오류 케이스(3-6, R 오류표) 점검.
7. 마지막에 **R-5 나가기**로 종료 동작 확인 → 재시작하려면 ② 시드 재실행.

---

## 5. 트러블슈팅

| 증상 | 원인/해결 |
|----|----|
| REST `401 JWT_401_INVALID` | `jwtSecret` 불일치 또는 32바이트 미만. 서버 `JWT_SECRET`과 동일하게 설정 |
| REST `401 JWT_401_EXPIRED` | 토큰 만료(기본 30분). Pre-request 재실행으로 갱신 |
| REST `403 NOT_PARTICIPANT` | 토큰의 `memberId`가 그 방 참여자가 아님. 시드의 `member_id`와 토큰 `sub` 일치 확인 |
| REST `404 ROOM_NOT_FOUND` | 방이 없거나 CLOSED. 시드 재확인(특히 나가기 후) |
| STOMP가 `CONNECTED`를 안 줌 | NULL 종료자 누락/토큰 무효/`/ws` 경로 오타. 부록 A 스니펫으로 재시도 |
| 구독했는데 메시지 미수신 | 구독 `destination`이 `/topic/rooms/{roomId}`인지, 송신은 `/app/rooms/{roomId}/send`인지 확인 |
| `/topic` 구독이 조용히 안 됨 | 비참여자 차단됨 — `/user/queue/errors` 구독 후 통지 확인 |

---

## 부록 A. StompJS 스니펫 (Postman WebSocket이 번거로울 때)

NULL 프레이밍·하트비트를 직접 다루지 않아도 되는 대안입니다. 브라우저 콘솔(아무 페이지) 또는 Node에서 실행하세요.
서버 STOMP는 표준이라 라이브러리 클라이언트와 그대로 호환됩니다.

```html
<!-- 브라우저: 콘솔에서 사용하려면 먼저 라이브러리 로드 -->
<script src="https://cdn.jsdelivr.net/npm/@stomp/stompjs@7/bundles/stomp.umd.min.js"></script>
```

```javascript
// A 사용자 클라이언트
const token = '<accessTokenA 값 붙여넣기>';
const client = new StompJs.Client({
  brokerURL: 'ws://localhost:8080/ws',
  connectHeaders: { Authorization: 'Bearer ' + token }, // CONNECT 네이티브 헤더로 전달
  debug: (s) => console.log(s),
  reconnectDelay: 0,
});
client.onConnect = () => {
  client.subscribe('/user/queue/errors', (m) => console.error('ERROR:', m.body));
  client.subscribe('/topic/rooms/1',     (m) => console.log('RECV:', m.body));

  // 송신
  client.publish({ destination: '/app/rooms/1/send', body: JSON.stringify({ type: 'TEXT', content: '안녕하세요 STOMP' }) });
  // 읽음
  client.publish({ destination: '/app/rooms/1/read', body: JSON.stringify({ lastReadMessageId: 3 }) });
};
client.onStompError = (f) => console.error('STOMP ERROR', f.headers, f.body);
client.activate();
```

- B 사용자는 위 스크립트를 `accessTokenB`로 한 번 더 실행하면 됩니다(별도 탭/콘솔).
- 단계 변경 확인: 한쪽 콘솔이 `/topic/rooms/1` 구독 중일 때, REST로 양쪽 `progress/agree`를 호출하면
  `{"eventType":"PROGRESS_CHANGED",...}`가 `RECV`로 찍힙니다.

## 부록 B. 빠른 참조 — STOMP 프레임 모음

```
# 연결
CONNECT
accept-version:1.2
host:localhost
Authorization:Bearer <AT>

^@

# 구독 (개인 오류)
SUBSCRIBE
id:sub-errors
destination:/user/queue/errors

^@

# 구독 (방 토픽)
SUBSCRIBE
id:sub-room-1
destination:/topic/rooms/1

^@

# 송신
SEND
destination:/app/rooms/1/send
content-type:application/json

{"type":"TEXT","content":"hello"}^@

# 읽음
SEND
destination:/app/rooms/1/read
content-type:application/json

{"lastReadMessageId":3}^@
```

> `^@` = NULL(`0000`) 1바이트. 각 프레임은 헤더와 본문 사이에 빈 줄 1개, 끝에 NULL이 필요합니다.

---

## 참고

- 기능 설계: [`docs/chat/CHAT_FEATURE.md`](./CHAT_FEATURE.md) §6(WebSocket/STOMP), §7(유스케이스), §9(보안), §10(에러 코드)
- 인증 흐름: [`docs/ARCHITECTURE.md § 8`](../ARCHITECTURE.md)
- 에러 코드 정의: `com.blursome.blursome.chat.exception.ChatErrorCode`, `global.exception.code.JwtErrorCode`
