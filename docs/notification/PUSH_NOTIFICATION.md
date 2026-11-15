# PWA 채팅 Web Push 알림 (#121)

앱을 닫았거나 백그라운드에 있어도 채팅 메시지를 시스템 알림으로 받게 한다. 표준 Web Push(Push API + VAPID)를 쓰며 FCM은 쓰지 않는다. Android Chrome, 데스크톱 브라우저, iOS/iPadOS 16.4 이상의 **홈 화면에 추가한 PWA**가 같은 경로로 받는다.

---

## 1. 역할 분리

| 경로 | 용도 | 동작 |
|---|---|---|
| STOMP `/topic/rooms/{roomId}`, `/user/queue/rooms` | 화면 데이터 갱신 | 소켓이 연결돼 있을 때만 받는다 |
| Web Push | 시스템 알림 | 소켓 연결 여부와 상관없이 **항상** 보낸다 |

- 서버는 소켓 접속 상태를 보고 푸시를 생략하지 않는다. iOS는 받은 푸시를 반드시 표시해야 하므로(무음 푸시를 반복하면 구독이 취소될 수 있음) 보낼지 말지는 서버가 정하고, 표시하지 않을 알림은 처음부터 보내지 않는다.
- 알림 설정은 **기기 단위**다. 계정 전체 on/off(서버 저장)는 두지 않는다. 알림 끄기 = 그 기기 구독 해제.

---

## 2. API

| 메서드 | 경로 | 인증 | 요청 | 성공 응답 |
|---|---|---|---|---|
| `GET` | `/api/push/public-key` | 불필요 | - | `200` `{ "data": { "publicKey": "<base64url>" } }` |
| `PUT` | `/api/push/subscriptions` | 필요 | `PushSubscription.toJSON()` 그대로 | `204` (upsert, 멱등) |
| `DELETE` | `/api/push/subscriptions` | 필요 | `{ "endpoint": "..." }` | `204` (없어도 204, 멱등) |
| `POST` | `/api/auth/logout` | 불필요 | 선택 `{ "endpoint": "..." }` | `204` |

**PUT body 예시**
```json
{
  "endpoint": "https://web.push.apple.com/QGuQyavXut...",
  "expirationTime": null,
  "keys": { "p256dh": "BNcRdreALRFX...", "auth": "tBHItJI5svbpez7KI4CCXg" }
}
```

**오류 응답**

| 상태 | 코드 | 상황 |
|---|---|---|
| 400 | `PUSH_400_ENDPOINT_NOT_ALLOWED` | https가 아니거나 허용된 푸시 서비스 호스트가 아님 |
| 400 | `PUSH_400_INVALID_KEYS` | `p256dh`가 65바이트 비압축 P-256 점이 아니거나 `auth`가 16바이트가 아님 |
| 400 | (공통 검증 오류) | `endpoint`·`keys` 누락 |
| 503 | `PUSH_503_NOT_CONFIGURED` | 서버에 VAPID 키가 설정되지 않음(공개 키 조회) |

### 구독 규칙
- 기준 키는 `endpoint`다. 길이가 최대 2048자라 DB에는 SHA-256 해시(`endpoint_hash`)에 유니크 제약을 건다.
- 같은 endpoint를 다른 계정이 등록하면 **소유 회원을 그 계정으로 바꾼다**(공용 기기 재로그인 시 이전 계정 알림이 가지 않게).
- 허용 호스트(`app.push.allowed-endpoint-hosts`): `fcm.googleapis.com`, `push.services.mozilla.com`, `push.apple.com`, `notify.windows.com`과 그 하위 도메인. 서버가 사용자가 넣은 임의 URL(내부망 등)로 요청하지 않게 막는다.

### 로그아웃
- body에 `endpoint`가 있으면 **RT 유효성과 무관하게** 그 기기 구독을 지운다. endpoint는 그 브라우저만 아는 값이라 남의 구독을 지울 위험이 낮고, 로그아웃한 기기로 알림이 계속 가는 것을 막는 쪽이 더 중요하다.
- body가 없으면 지금처럼 RT만 무효화한다(하위 호환).

---

## 3. 발송

```
ChatMessageService.send (트랜잭션)
  └─ ChatRoomNotificationEvent(NEW_MESSAGE) 발행  ← 기존 STOMP 개인 알림과 같은 이벤트
        ├─ ChatRoomNotificationListener (AFTER_COMMIT)       → STOMP /user/queue/rooms
        └─ ChatPushListener (AFTER_COMMIT, @Async pushExecutor) → ChatPushService → WebPushSender
```

- **커밋된 메시지만** 보낸다(롤백 시 미발송). 외부 HTTP 호출은 전용 실행기(`push-*` 스레드, 큐 500)에서 비동기로 보내 채팅 송신을 막지 않는다. 큐가 가득 차면 그 알림은 버리고 로그만 남긴다.
- **첫 메시지 중복 방지**: 첫 메시지로 방이 열리면 같은 메시지에 대해 `NEW_ROOM`(`ChatRoomCreator`)과 `NEW_MESSAGE`(`ChatMessageService.send`)가 함께 발행된다. 푸시 리스너는 `NEW_MESSAGE`만 처리하므로 메시지 하나당 기기마다 푸시는 한 번이다.
- **대상**: 수신자(상대)의 모든 구독 기기.
- **보내지 않는 경우**: VAPID 미설정, `SYSTEM` 메시지, 발신자 본인, 수신 기기 없음, 수신자가 나간 방, 방이 `ACTIVE`가 아님(차단·신고 동결, 종료·탈퇴). 방 상태는 커밋 이후 발송 직전에 다시 확인한다.
- **헤더**: `TTL: 86400`(1일), `Urgency: high`, `Topic: room-{roomId}`(푸시 서비스에 대기 중인 같은 방 알림을 최신 것으로 합침), `Content-Encoding: aes128gcm`, VAPID `Authorization`.
- **결과 처리**: 2xx → `last_success_at` 갱신. `404`/`410` → 구독 삭제(만료·해지). 그 밖의 실패(5xx, 429, 타임아웃 5초 등)는 로그만 남기고 채팅 흐름에 영향을 주지 않는다. 재시도는 하지 않는다.
- **회원 탈퇴**: `MemberWithdrawnEvent`(AFTER_COMMIT)에서 그 회원의 구독을 모두 지운다.

### 페이로드

```json
{
  "type": "NEW_MESSAGE",
  "roomId": 12,
  "messageId": 345,
  "title": "발신자닉네임",
  "body": "메시지 앞부분 최대 80자…",
  "url": "/chat/12"
}
```

| 필드 | 규칙 |
|---|---|
| `type` | 항상 `NEW_MESSAGE`(첫 메시지 포함) |
| `title` | 발신자 닉네임. 조회 실패 시 `BlurSome` |
| `body` | 텍스트: 공백·줄바꿈을 한 칸으로 줄인 뒤 앞 80자(코드 포인트 기준), 넘으면 `…`. 이미지: `사진을 보냈어요` |
| `url` | `/chat/{roomId}` (프론트 경로 `/chat/:roomId`) |

---

## 4. 프론트 연동 가이드

### 서비스 워커
```js
self.addEventListener('push', (event) => {
  const data = event.data ? event.data.json() : {};
  // iOS: 받은 푸시는 앱이 열려 있어도 반드시 표시해야 한다.
  event.waitUntil(self.registration.showNotification(data.title ?? 'BlurSome', {
    body: data.body,
    tag: `room-${data.roomId}`,   // 같은 방 알림을 하나로 합친다(선택)
    data: { url: data.url, roomId: data.roomId, messageId: data.messageId },
  }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(clients.openWindow(event.notification.data.url));
});

self.addEventListener('pushsubscriptionchange', (event) => {
  // 브라우저가 구독을 교체하면 새 구독으로 PUT을 다시 보낸다.
});
```

### 구독 흐름
1. `GET /api/push/public-key`로 공개 키를 받는다.
2. **사용자 탭 같은 직접 동작 안에서** `Notification.requestPermission()`을 호출한다(iOS 필수).
3. `registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: publicKey })`
4. `PUT /api/push/subscriptions`에 `subscription.toJSON()`을 보낸다. 앱 시작 시마다 다시 보내도 멱등이다.
5. 알림 끄기: `subscription.unsubscribe()` 후 `DELETE /api/push/subscriptions` `{ endpoint }`.
6. 로그아웃: `POST /api/auth/logout`에 `{ endpoint }`를 함께 보낸다.

### iPhone(iOS) 주의
- **홈 화면에 추가한 PWA에서만** 동작한다(Safari 탭 X). iOS 16.4 이상.
- 권한 요청은 사용자 제스처 안에서만 띄울 수 있다.
- 받은 푸시를 표시하지 않으면 iOS가 구독을 취소할 수 있다.

---

## 5. 설정·배포

| 환경 변수 | 설명 | 기본값 |
|---|---|---|
| `VAPID_PUBLIC_KEY` | VAPID 공개 키(base64url, 비압축 P-256 점) | 비어 있음 → 푸시 비활성 |
| `VAPID_PRIVATE_KEY` | VAPID 개인 키(base64url, 32바이트) | 비어 있음 → 푸시 비활성 |
| `VAPID_SUBJECT` | 푸시 서비스 연락처(`mailto:` 또는 URL) | `mailto:admin@blursome.com` |
| `PUSH_TTL` | 오프라인 기기용 보관 시간 | `1d` |
| `PUSH_REQUEST_TIMEOUT` | 푸시 서비스 요청 타임아웃 | `5s` |

- 키 생성: `npx web-push generate-vapid-keys` 결과의 Public Key / Private Key를 그대로 넣는다.
- **키 쌍은 한 번 만들어 고정한다.** 바꾸면 기존 구독이 모두 무효가 되고, 프론트가 새 공개 키로 다시 구독해야 한다.
- 운영 서버 `.env`(docker-compose `env_file`)에 위 값을 추가한다. 키가 비어 있으면 컨텍스트는 뜨지만 발송을 건너뛰고 공개 키 API는 503을 준다. 키 형식이 틀리면 기동 시 실패한다.
- 테이블 `push_subscription`은 `ddl-auto: update`로 생성된다.

### 라이브러리
- `nl.martijndwars:web-push:5.1.1` — 암호화(aes128gcm)와 VAPID 서명만 사용한다. 전송은 JDK `HttpClient`로 직접 한다(타임아웃 통제).
- 동기 전송만 쓰므로 `async-http-client`(Netty)·`jcommander`는 제외했다. `bcprov-jdk18on`은 직접 추가하고, `jose4j`는 보안 패치 버전(0.9.6)으로 올렸다.
