# 채팅 기능 로직 문서

BlurSome의 채팅(Chat) 도메인 전체 로직을 정의합니다. WebSocket + STOMP 기반 실시간 메시지 송수신과, 매칭 이후 사진을 단계적으로 공개해 가는 1:1
대화 흐름을 다룹니다.

> 상태 표기
> - ✅ **확정**: 엔티티·결정으로 코드/스키마에 반영됨
> - 🧩 **설계(제안)**: 방향은 정했으나 구현 전 — 검토 후 조정 가능
> - ⏳ **미정(TODO)**: 결정이 필요한 열린 항목

전반 아키텍처 규칙은 [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md), 코드 컨벤션은 [
`docs/CODE_CONVENTION.md`](../CODE_CONVENTION.md)를 따릅니다.

---

## 1. 개요

### 목표

- 매칭된 두 회원이 실시간으로 대화한다.
- 대화가 진행됨에 따라 **상호 동의**로 사진 공개 단계(`progressStatus`)를 올린다.
- 한쪽이 나가거나 대화가 종료되면 방을 `CLOSED` 처리한다.

### 핵심 결정 (확정)

| 항목              | 결정                                 | 영향                                                       |
|-----------------|------------------------------------|----------------------------------------------------------|
| 참여 구조           | **1:1 고정(2명)**                     | 방당 `ChatRoomMember` 정확히 2행, 상대방 조회 단순                    |
| 재입장             | **불가** — 나가면 방 종료(`CLOSED`), 재개 없음 | 다시 대화하려면 새 매칭 → 새 방 개설                                   |
| 접속 상태(presence) | **미제공** — 상대 온라인 여부를 표시하지 않음       | `lastActiveAt` 등 활동 시각 필드를 두지 않음. 앱 종료/연결 끊김은 DB에 기록 안 함 |
| 단계 진행           | **상호 동의** (양쪽 수락 필요)               | `ChatRoomMember.agreedProgressStatus` 컬럼으로 각자 동의 단계 보관   |

### 전제 / 선행 작업

- ✅ **의존성 추가됨**: `build.gradle.kts`에 `spring-boot-starter-websocket` 추가 완료.
- 인증은 기존 JWT 체계를 그대로 사용 (`docs/ARCHITECTURE.md § 8`).
- 매칭(두 회원을 이어주는 로직)은 채팅 도메인 밖에서 일어나며, 그 결과로 방이 개설된다고 가정한다. (매칭 도메인은 본 문서 범위 밖)

---

## 2. 도메인 모델

### 엔티티 관계

```
        매칭 성사
           │
           ▼
       ┌─────────┐         1 : 2        ┌──────────────────┐        N : 1
       │ ChatRoom │◀────────────────────│ ChatRoomMember   │───────────────▶ Member
       └─────────┘                      └──────────────────┘
           ▲ 1
           │
           │ N
       ┌──────────────┐  sender (N:1)
       │ ChatMessage  │──────────────▶ Member
       └──────────────┘
```

### ChatRoom (`chat_room`)

대화방 1개. 1:1이므로 참여자는 `ChatRoomMember` 2행으로 표현한다.

| 필드                      | 타입 / 제약                                            | 설명                                                      |
|-------------------------|----------------------------------------------------|---------------------------------------------------------|
| `id`                    | PK, IDENTITY                                       |                                                         |
| `roomStatus`            | `ChatRoomStatus`, `nullable=false`, len 20         | 방 활성/종료 상태                                              |
| `progressStatus`        | `ChatRoomProgressStatus`, `nullable=false`, len 30 | 사진 공개 단계                                                |
| `lastMessageId`         | `Long`, nullable                                   | **미리보기 비정규화** — 마지막 메시지 id. FK 아님(무결성 미보장), 메시지 저장 시 갱신 |
| `activePairKey`         | `String`, nullable, **unique** `uk_chat_room_active_pair`, len 40 | 두 참여 회원 id를 정렬한 키(`min-max`). ACTIVE 동안만 값 보유, 종료 시 `null`. 유니크 제약으로 **회원 쌍당 ACTIVE 방 1개**를 DB 레벨에서 보장(동시 개설 방지). 유니크 인덱스는 다중 `null`을 허용하므로 같은 페어의 CLOSED 방은 여러 개 가능 |
| `createdAt`/`updatedAt` | `BaseEntity`                                       | 감사 필드                                                   |

- 팩토리: `ChatRoom.createOnMatched(memberAId, memberBId)` → `ACTIVE` / `MATCHED` / `lastMessageId=null` / `activePairKey=min-max`
- `close()`는 `roomStatus=CLOSED`로 바꾸며 `activePairKey=null`로 비워 같은 페어의 새 방 개설을 허용한다.

> ⚠️ **스키마/마이그레이션 주의 — 유니크 제약 누락 금지**
> `uk_chat_room_active_pair (active_pair_key)` 유니크 제약은 동시 개설 시 ACTIVE 방 중복 생성을 막는 **유일한 최종 방어선**이다(서비스 계층 선조회만으로는 경합을 막지 못함).
> - 운영(`prod`)은 `spring.jpa.hibernate.ddl-auto: validate`라 **Hibernate가 제약을 생성하지 않는다**(컬럼/테이블 존재만 검증). 따라서 운영 스키마에는 이 유니크 제약을 **반드시 별도로 반영**해야 한다.
> - 로컬(`local`)은 `ddl-auto: update`지만 기존 테이블에 유니크 제약을 사후 추가해주지 않을 수 있으므로, 검증 시 실제 인덱스 생성 여부를 확인한다.
> - 제약이 누락되면 동시 매칭에서 같은 페어의 ACTIVE 방이 중복 생성될 수 있고, `CHAT_409_ROOM_CREATION_CONFLICT` 방어도 무력화된다.

### ChatRoomMember (`chat_room_member`)

방-회원 참여 관계. 읽음 위치와 단계 동의 상태를 멤버별로 보관한다.

| 필드                     | 타입 / 제약                                            | 설명                                   |
|------------------------|----------------------------------------------------|--------------------------------------|
| `id`                   | PK, IDENTITY                                       |                                      |
| `chatRoom`             | `@ManyToOne(LAZY)`, `nullable=false`               |                                      |
| `member`               | `@ManyToOne(LAZY)`, `nullable=false`               |                                      |
| `joinedAt`             | `LocalDateTime`, `nullable=false`                  | 입장 시각. 재입장 시 갱신(그래서 `createdAt`과 별도) |
| `leftAt`               | `LocalDateTime`, nullable                          | 퇴장 시각. `null`이면 현재 참여 중              |
| `lastReadMessageId`    | `Long`, nullable                                   | 마지막으로 읽은 메시지 id → 안읽음 카운트 기준점        |
| `agreedProgressStatus` | `ChatRoomProgressStatus`, `nullable=false`, len 30 | 이 멤버가 동의한 단계. 양쪽이 같으면 방 단계 상승        |
| 유니크 제약                 | `uk_chat_room_member (chat_room_id, member_id)`    | 같은 방에 동일 회원 1행 보장 → 재입장은 행 재사용       |

- 팩토리: `ChatRoomMember.join(room, member)` → `joinedAt=now`, `agreedProgressStatus=MATCHED`,
  `leftAt=null`

### ChatMessage (`chat_message`)

| 필드         | 타입 / 제약                                     | 설명                        |
|------------|---------------------------------------------|---------------------------|
| `id`       | PK, IDENTITY                                | 메시지 순서 기준(시간순 ≈ id순)      |
| `chatRoom` | `@ManyToOne(LAZY)`, `nullable=false`        |                           |
| `sender`   | `@ManyToOne(LAZY)`, **nullable** (optional) | 보낸 회원. `SYSTEM` 메시지는 `null` |
| `content`  | `@Lob`, `nullable=false`                    | 본문. IMAGE 타입이면 URL/식별자 저장 |
| `type`     | `ChatMessageType`, `nullable=false`, len 20 | TEXT / IMAGE              |
| 인덱스        | `idx_chat_message_room (chat_room_id, id)`  | 방별 메시지 페이지네이션             |

- 팩토리: `createTextMessage(room, sender, content)`, `createImageMessage(room, sender, imageUrl)`, `createSystemMessage(room, content)`
- ✅ `SYSTEM` 메시지는 `sender = null`로 저장한다. 이를 위해 `sender` 연관을 nullable로 완화했다(`@ManyToOne` `optional` 기본값 유지, `@JoinColumn`에서 `nullable=false` 제거).

### Enum

| Enum                     | 값                                                 | 비고                                      |
|--------------------------|---------------------------------------------------|-----------------------------------------|
| `ChatRoomStatus`         | `ACTIVE`, `CLOSED`, `BLOCKED`, `REPORTED`         | BLOCKED/REPORTED는 후순위 기능                |
| `ChatRoomProgressStatus` | `MATCHED` → `PHOTO_REVEAL_STEP_1~4` → `COMPLETED` | **순서가 의미를 가짐**(ordinal 비교로 단계 비교)       |
| `ChatMessageType`        | `TEXT`, `IMAGE`, `SYSTEM`                         | `SYSTEM` = 단계 안내 등 시스템 생성 메시지(사람 발신 아님) |

---

## 3. 상태 머신

### 3-1. ChatRoomStatus (✅ 확정 / 일부 후순위)

```
            createOnMatched()
                  │
                  ▼
              ┌────────┐  한쪽 퇴장 / 대화 종료   ┌────────┐
              │ ACTIVE │ ───────────────────────▶│ CLOSED │
              └────────┘                          └────────┘
                  │
                  │ (후순위)
                  ├── 차단 ─────▶ BLOCKED
                  └── 신고 ─────▶ REPORTED
```

- `ACTIVE → CLOSED`: 한 명이 "채팅방 나가기"를 하거나(`leave()`), 명시적으로 대화를 종료할 때.
- ✅ **`CLOSED`는 종료(terminal) 상태** — 재입장 불가. 다시 `ACTIVE`로 되돌리지 않는다. 다시 대화하려면 새 매칭으로 새 방을 개설한다.
- ✅ **앱 종료 / 연결 끊김은 상태 변화가 아니다** — 상대 접속 상태(presence)를 제공하지 않으므로, 단순 연결 종료는 DB를 건드리지 않고 재연결 시 그대로
  대화를 이어간다.
- `BLOCKED`/`REPORTED`는 후순위.

### 3-2. ChatRoomProgressStatus — 상호 동의 진행 (✅ 확정 모델)

각 멤버는 자신의 `agreedProgressStatus`를 보유한다. **두 멤버의 동의 단계가 모두 다음 단계 이상**이 되면 방의 `progressStatus`가 한 단계
오른다.

```
방.progressStatus:  MATCHED ─▶ STEP_1 ─▶ STEP_2 ─▶ STEP_3 ─▶ STEP_4 ─▶ COMPLETED

승급 규칙(다음 단계 N으로):
   memberA.agreedProgressStatus >= N  AND  memberB.agreedProgressStatus >= N
        └──────────────── 둘 다 만족하면 ───────────────┘
                              ▼
                  room.progressStatus = N
```

- 한쪽만 동의한 상태는 "대기" — 방 단계는 그대로.
- 단계 비교는 enum 선언 순서(ordinal)를 이용한다. **따라서 enum 값 순서를 바꾸거나 중간 삽입하면 안 됨**(append만 허용).
- ✅ **단계 비교 헬퍼(구현됨)**: `ChatRoomProgressStatus.isAtLeast(other)` / `next()` / `isLast()` — 서비스가 ordinal을 직접 다루지 않도록 한다.
- ✅ **도메인 메서드(구현됨)**:
    - `ChatRoomMember.agreeProgress(target)` : 본인 동의 단계 상승 — **되돌리기(취소) 비허용**(단조 증가, 후퇴 시 예외)
    - `ChatRoom.advanceProgressIfBothAgreed(a, b)` : 양쪽 동의가 다음 단계 이상이면 한 단계 상승, 변경 여부(`boolean`) 반환
    - `ChatRoom.close()` : `roomStatus = CLOSED` (이미 종료면 멱등)

### 3-3. ChatRoomMember 생명주기 (나가기 = 종료, 재입장 불가)

```
join(room, member)   (leftAt = null)
        │
        ▼
   참여 중 ──── leave() ───▶ leftAt = now ──▶ (종료 · 재입장 불가)
```

- ✅ 한 번 나가면(`leftAt` 세팅) 그 방으로는 다시 들어올 수 없다. 1:1이므로 한쪽 나가기는 방 종료(`CLOSED`)로 이어진다.
- `(chat_room_id, member_id)` 유니크 제약은 유지된다(회원이 한 방에 한 번만 참여하므로 행도 1개).
- ✅ `leave()` 도메인 메서드(구현됨): `leftAt = now` 설정(이미 나갔으면 예외). 읽음 위치는 `readUpTo(messageId)`로 전진만. `rejoin()`은 정책상 제공하지 않는다.
- ✅ **앱 종료 / 일시적 연결 끊김은 여기에 해당하지 않는다** — `leftAt`을 건드리지 않으며, 활동 시각(`lastActiveAt`)도 두지 않는다(상대 접속 상태
  미제공).

---

## 4. 패키지 구조 (✅ 구현)

`docs/ARCHITECTURE.md § 3`의 레이어 서브패키지 규칙을 따른다.

```
com.blursome.chat
├── controller/
│   ├── ChatRoomController.java          # ✅ REST: 방 목록/단건/이력 조회, 단계 동의, 종료
│   └── ChatStompController.java         # ✅ @MessageMapping: 실시간 송신/읽음 + STOMP 예외 → 개인 오류 큐
├── service/
│   ├── ChatRoomService.java             # ✅ 방 개설/조회/종료/단계 진행 (Facade)
│   ├── ChatMessageService.java          # ✅ 메시지 저장/조회/송신/읽음 처리
│   └── ChatRoomMembershipReader.java    # ✅ 참여자 가시성 검증 공용(REST·STOMP 공유, 순환 의존 회피)
├── repository/
│   ├── ChatRoomRepository.java          # ✅ (advanceLastMessage: 미리보기 원자적 전진)
│   ├── ChatRoomMemberRepository.java    # ✅
│   └── ChatMessageRepository.java       # ✅ (existsByIdAndChatRoom_Id: 읽음 커서 검증)
├── domain/
│   ├── ChatRoom.java                    # ✅
│   ├── ChatRoomMember.java              # ✅
│   ├── ChatMessage.java                 # ✅
│   ├── ChatRoomStatus.java              # ✅
│   ├── ChatRoomProgressStatus.java      # ✅
│   └── ChatMessageType.java             # ✅
├── dto/
│   ├── request/                         # ✅ ChatMessageSendRequest, ChatReadRequest
│   └── response/                        # ✅ ChatRoomSummaryResponse, ChatMessageResponse, ChatProgressChangedResponse
├── event/
│   ├── ChatProgressAdvancedEvent.java   # ✅ 단계 상승 도메인 이벤트
│   └── ChatProgressEventListener.java   # ✅ AFTER_COMMIT 구독 → /topic 브로드캐스트
├── config/
│   ├── WebSocketConfig.java             # ✅ STOMP 엔드포인트/브로커 설정
│   └── StompAuthChannelInterceptor.java # ✅ CONNECT 인증 + SUBSCRIBE 참여자 검증
└── exception/
    └── ChatErrorCode.java               # ✅
```

> ✅ WebSocket 설정 위치 결정: 채팅 도메인 한정이므로 `chat/config/`에 둔다(전역 `global/` 아님).

---

## 5. 통신 아키텍처 — REST vs WebSocket 역할 분담 (✅ 구현)

| 작업                | 채널                           | 이유                                   |
|-------------------|------------------------------|--------------------------------------|
| 방 목록/단건 조회        | REST `GET`                   | 상태성 없는 단순 조회                         |
| 과거 메시지 이력(페이지네이션) | REST `GET`                   | 커서 기반 조회는 요청-응답이 적합                  |
| **실시간 메시지 송신**    | WebSocket(STOMP `SEND`)      | 양방향 푸시                               |
| **실시간 메시지 수신**    | WebSocket(STOMP `SUBSCRIBE`) | 브로드캐스트                               |
| 읽음 처리             | **WebSocket 우선** (STOMP)     | ✅ 실시간성 우선. 읽음 위치 갱신은 STOMP, REST는 보조 |
| 단계 동의             | REST `POST`                  | 트랜잭션·검증이 중요, 빈도 낮음                   |
| 방 종료/나가기          | REST `POST`                  | 명시적 행위                               |

> 원칙: **영속·조회 트랜잭션은 Service(Facade)** 가 담당하고, WebSocket 컨트롤러도 동일하게 Service를 경유한다. STOMP 핸들러에 비즈니스
> 로직을 직접 넣지 않는다.

---

## 6. WebSocket + STOMP 설계 (✅ 구현)

### 6-1. 엔드포인트 / 브로커

| 항목                      | 값                  | 비고                               |
|-------------------------|--------------------|----------------------------------|
| STOMP 핸드셰이크             | `GET /ws`          | ✅ SockJS 폴백 초기 미사용(순수 WebSocket) |
| 앱 prefix (클라이언트 → 서버)   | `/app`             | `@MessageMapping`과 매칭            |
| 브로커 prefix (서버 → 클라이언트) | `/topic`, `/queue` | 단순 in-memory 브로커로 시작             |
| 유저 destination prefix   | `/user`            | 개인 대상 메시지                        |

### 6-2. Destination 규칙 (✅ 구현)

| 방향    | Destination                | 설명                                            |
|-------|----------------------------|-----------------------------------------------|
| 구독    | `/topic/rooms/{roomId}`    | ✅ 해당 방의 메시지·단계 변경 브로드캐스트 수신(구독 시 참여자 검증)        |
| 송신    | `/app/rooms/{roomId}/send` | ✅ 메시지 전송 → 서버가 저장 후 `/topic/rooms/{roomId}`로 발행 |
| 송신    | `/app/rooms/{roomId}/read` | ✅ 읽음 위치 갱신 (WebSocket 우선)                     |
| 개인 알림 | `/user/queue/errors`       | ✅ 검증 실패 등 발신자 개인 응답(해당 세션 한정)                  |

### 6-3. 핸드셰이크 인증 (JWT, ✅ 구현)

- ✅ STOMP `CONNECT` 프레임 헤더(`Authorization: Bearer <accessToken>`)에서 토큰 추출.
- ✅ `StompAuthChannelInterceptor`(`configureClientInboundChannel`)에서 `JwtTokenProvider.parseAccess`로 검증 →
  복원한 `JwtAuthentication`을 STOMP 세션 principal(`StompHeaderAccessor.setUser`)로 저장. 인증 실패 시 CONNECT 거절.
- ✅ 이후 `@MessageMapping` 핸들러에서 principal(`memberId`)로 발신자를 식별(클라이언트가 보낸 senderId를 신뢰하지 않음).
- ⏳ 토큰 만료가 연결 도중 발생할 때의 처리(재연결 유도) 정책 필요.

### 6-4. 페이로드 DTO (✅ 구현)

```jsonc
// 클라 → 서버 : /app/rooms/{roomId}/send
{ "type": "TEXT", "content": "안녕하세요" }

// 서버 → 구독자 : /topic/rooms/{roomId}
{
  "messageId": 1024,
  "roomId": 7,
  "senderId": 3001,
  "type": "TEXT",
  "content": "안녕하세요",
  "createdAt": "2026-06-04T12:00:00"
}
```

### 6-5. 브로커 확장 전략 (✅ 단계별 확정)

| 단계      | 시점                   | 브로커 구성                                    |
|---------|----------------------|-------------------------------------------|
| **1단계** | 현재                   | **단일 인스턴스 + Simple(in-memory) Broker**    |
| **2단계** | 서버 2대 이상 확장 직전       | **Redis Pub/Sub 릴레이** 검토 (인스턴스 간 메시지 팬아웃) |
| **3단계** | 메시지 전달 보장·큐잉 요구 증가 시 | **RabbitMQ** STOMP 브로커 릴레이 검토             |

- in-memory 브로커는 단일 인스턴스 한정이므로, 수평 확장 시점에 2단계로 전환한다.
- 현재 프로젝트는 이미 Redis를 보유(토큰/캐시)하므로 2단계 진입 비용이 낮다.
- 각 단계 전환은 ADR로 기록한다(`docs/ARCHITECTURE.md § 9`).

---

## 7. 핵심 유스케이스 흐름

### 7-1. 매칭 → 방 개설 (🧩)

```
[매칭 결과: memberA, memberB]
        │
        ▼
ChatRoomService.openRoom(a, b)        @Transactional
    ├─ 활성 방 선조회(findActiveRoomBetween) → 있으면 그 방 반환
    └─ 없으면 createRoom:
        ├─ ChatRoom.createOnMatched(a, b)         → saveAndFlush (ACTIVE / MATCHED / activePairKey)
        ├─ ChatRoomMember.join(room, a)           → save
        └─ ChatRoomMember.join(room, b)           → save
        │
        ▼
   room 반환 (양쪽에 푸시/알림은 Notification 도메인과 조율)
```

- ✅ **중복 방 생성 방지 (서비스 + DB 이중 방어)**: 두 회원 사이에 `ACTIVE` 방이 이미 있으면 새로 만들지 않고 그 방을 반환한다.
  과거에 나가서 `CLOSED`된 방만 있는 경우에는 **새 방을 개설**한다. → `ChatRoomService.openRoom`에서 활성 방을 먼저 조회해 분기.
- ✅ **동시 매칭 경합**: 선조회-후생성은 원자적이지 않아 동시 요청 시 중복이 생길 수 있으므로, `chat_room.active_pair_key` **유니크 제약**으로 최종 방어한다.
  경합에서 진 쪽은 `saveAndFlush`에서 제약 위반(`DataIntegrityViolationException`) → `CHAT_409_ROOM_CREATION_CONFLICT`로 변환되며, 재시도 시 선조회가 이미 만들어진 방을 반환한다.
- ✅ **조회 시 활성 방만 노출**: 1:1 방에서 한쪽이 나가면 방은 `CLOSED`가 되지만 상대의 `leftAt`은 `null`로 남는다. 따라서 목록/단건/이력 조회의 참여자 검증은 `leftAt IS NULL` + `roomStatus = ACTIVE`를 함께 확인한다.

### 7-2. 연결 & 구독

```
[Client] ─ CONNECT(Authorization: Bearer ...) ─▶ /ws
[Client] ─ SUBSCRIBE ─▶ /topic/rooms/{roomId}     (참여자 검증 후 허용)
```

- ✅ 구독 시 해당 회원이 그 방의 참여자(`ChatRoomMember`, `leftAt IS NULL`)인지 검증한다(`StompAuthChannelInterceptor`의
  `SUBSCRIBE` 처리). 비참여자 구독은 연결을 끊지 않고 해당 세션의 개인 오류 큐(`/user/queue/errors`)로 통지한 뒤 프레임을
  드롭해 차단한다(`preSend`가 null 반환).

### 7-3. 메시지 송수신

```
[Client A] ─ SEND /app/rooms/{roomId}/send {type, content} ─▶ ChatStompController
                                                                    │
                                                                    ▼
                                              ChatMessageService.send(roomId, senderId, dto)  @Transactional
                                                ├─ 참여자/방 ACTIVE 검증
                                                ├─ ChatMessage.createTextMessage(...) → save
                                                ├─ ChatRoom.updateLastMessage(message.id)   (미리보기 갱신)
                                                └─ 알림 생성(Notification 도메인, 오프라인 상대)
                                                                    │
                                                                    ▼
                              messagingTemplate.convertAndSend("/topic/rooms/{roomId}", response)
                                                                    │
                                                          ┌─────────┴─────────┐
                                                          ▼                   ▼
                                                     [Client A]           [Client B]
```

- ✅ **구현**: `ChatStompController.send` → `ChatMessageService.send`(쓰기 트랜잭션)에서 참여자·방 ACTIVE 검증 후 저장하고
  `SimpMessagingTemplate`으로 `/topic/rooms/{roomId}`에 `ChatMessageResponse`를 발행한다. 발신자는 STOMP principal에서
  결정한다(§9). 미리보기 갱신은 §8의 원자적 전진(역행 방지)으로 처리한다.
- ⏳ 오프라인 상대 알림 생성(Notification 도메인 연동)은 후속 작업.

### 7-4. 읽음 처리 & 안읽음 카운트 (✅ 구현 / 🧩 캐싱 예정)

- ✅ 읽음 위치 갱신은 WebSocket 경로(`/app/rooms/{roomId}/read` → `ChatMessageService.markAsRead`)로 처리하며,
  `ChatRoomMember.lastReadMessageId`를 전진(`readUpTo`)시킨다.
- ✅ **커서 검증**: 클라이언트가 보낸 `lastReadMessageId`가 그 방에 실제 존재하는 메시지인지(`existsByIdAndChatRoom_Id`)
  확인한 뒤에만 전진시킨다. 방에 없는 큰 id(예: `Long.MAX_VALUE`)로 안읽음 카운트를 영구 무력화하는 것을 막는다(§9).
- 안읽음 수 = `count(ChatMessage where chatRoom=room and id > lastReadMessageId and sender != me)`.
- ✅ **초기에는 DB count**로 계산한다(단순·정확). 트래픽이 늘면 Redis 캐싱으로 전환:
  `blursome:chat:<roomId>:<memberId>:unread` (키 스킴은 `docs/ARCHITECTURE.md § 6 Redis` 준수). 전환은 ADR로
  기록.

### 7-5. 단계 진행 (상호 동의)

```
[Client A] ─ POST /api/chat/rooms/{roomId}/progress/agree ─▶ ChatRoomController
                                                                  │
                                                                  ▼
                                       ChatRoomService.agreeProgress(roomId, memberId)  @Transactional
                                         ├─ memberA.agreeProgress(next)        (본인 동의 단계 상승)
                                         ├─ room.advanceProgressIfBothAgreed(a, b)
                                         │     └─ 둘 다 동의 → room.progressStatus++
                                         └─ 변경 시 /topic/rooms/{roomId}로 단계 변경 이벤트 발행
```

- ✅ **구현**: `ChatRoomService.agreeProgress`가 단계 상승 시 `ChatProgressAdvancedEvent`를 발행하고,
  `ChatProgressEventListener`가 `@TransactionalEventListener(AFTER_COMMIT)`로 받아 `/topic/rooms/{roomId}`에
  `ChatProgressChangedResponse`(`eventType=PROGRESS_CHANGED`)를 브로드캐스트한다. 커밋 이후에만 발행하므로 롤백 시
  오발송이 없다. 같은 토픽의 메시지 응답과는 `eventType`으로 구분한다.
- 한쪽만 동의 → 방 단계 변화 없음, 상대에게 "동의 대기" 표시(선택).
- ✅ **역할 분리**: Chat 도메인은 **단계(`progressStatus`)만 관리**한다. 각 단계에 대응하는 사진(블러 해제 대상)의 저장·제공은 프로필/회원 도메인
  책임이다.
- 단계가 오르면 클라이언트는 해당 단계에 설정된 사진의 블러를 해제해 공개한다. Chat은 "현재 몇 단계인지"만 알리고, 사진 자체는 보유·전송하지 않는다.

### 7-6. 채팅방 나가기 (종료)

명시적 "채팅방 나가기"만 영구 처리한다. 앱 종료/연결 끊김은 아무 것도 저장하지 않는다(상대 접속 상태 미제공).

```
채팅방 나가기:  POST /api/chat/rooms/{roomId}/leave
   ├─ ChatRoomMember.leave()      (leftAt = now)
   └─ ChatRoom.close()            (roomStatus = CLOSED)   ← 1:1이므로 한쪽 나가기 = 종료
   → 재입장 불가. 다시 대화하려면 새 매칭으로 새 방 개설.

앱 종료 / 연결 끊김:  (DB 변경 없음)
   └─ 재연결 + /topic/rooms/{roomId} 재구독
      → lastReadMessageId 기준으로 안읽음을 계산해 대화를 그대로 이어간다.
```

---

## 8. 데이터 접근 & 성능

| 관심사          | 방식                                                                                              |
|--------------|-------------------------------------------------------------------------------------------------|
| 방별 메시지 이력    | `idx_chat_message_room (chat_room_id, id)` 기반, **id 커서 페이지네이션**(`id < lastSeenId` desc limit N) |
| 마지막 메시지 미리보기 | `ChatRoom.lastMessageId` 비정규화 — 방 목록 조회 시 N+1/정렬 비용 절감. ✅ 동시 송신 경합에서 과거 id로 되돌아가지 않도록 조건부 UPDATE(`ChatRoomRepository.advanceLastMessage`, `lastMessageId < :id`일 때만 전진)로 갱신 |
| 안읽음 카운트      | `lastReadMessageId` 기준 카운트, 필요 시 Redis 캐싱                                                       |
| 내 방 목록       | `ChatRoomMember`에서 `member=me and leftAt IS NULL`로 조회 후 방 정보 조인                                 |

- Repository는 `JpaRepository<Entity, Long>` 상속, 복잡 쿼리는 JPQL `@Query` (ARCHITECTURE § 6).

---

## 9. 보안 / 권한 규칙

1. ✅ **참여자만**: 메시지 송신·구독·읽음·이력 조회는 해당 방의 활성 참여자(`leftAt IS NULL`)만 가능. 가시성 검증은
   `ChatRoomMembershipReader`로 단일화하며, 조회용(`getVisibleMembership`)과 쓰기용(`getWritableMembership`)을 분리한다.
2. ✅ **발신자 위조 방지**: 발신자는 클라이언트 입력이 아닌 STOMP principal(JWT의 `memberId`)로 결정.
3. ✅ **방 상태 검증**: `CLOSED` 방에는 송신·읽음 불가. 쓰기 경로는 종료를 `ROOM_CLOSED`(409)로 명시한다(§10).
4. ✅ **읽음 커서 위조 방지**: `lastReadMessageId`가 그 방의 실제 메시지인지 검증해(§7-4) 조작으로 안읽음 카운트를 무력화하지 못하게 한다.
5. ✅ **인가/처리 실패 통지**: REST는 `BaseException.from(ChatErrorCode.*)` → `GlobalExceptionHandler`가 `ErrorResponse` 변환.
   STOMP 경로는 `/user/queue/errors`로 개인 통지하며, 같은 계정의 다른 세션에 새지 않도록 해당 세션(`simpSessionId`)에만 보낸다.
   STOMP 핸들러는 도메인 예외(`BaseException`)·입력 오류(`@Valid`·변환 → `INVALID_MESSAGE`)·그 밖의 예외(기록 후 500)를 분리 처리한다.

---

## 10. 에러 코드 (✅ 구현)

`com.blursome.chat.exception.ChatErrorCode` (`ErrorCode` 구현 Enum):

> ✅ **경로별 매핑 차이**: 조회(REST)는 종료된 방을 노출하지 않도록 `ROOM_NOT_FOUND`(404)로 숨기고, 쓰기(STOMP 송신·읽음)는
> 종료를 `ROOM_CLOSED`(409)로 명시한다. 방 없음(`ROOM_NOT_FOUND`)·비참여(`NOT_PARTICIPANT`) 판별은 두 경로가 동일하다.
> 잘못된 본문/타입(빈 본문·`SYSTEM` 송신·조작된 읽음 커서)은 `INVALID_MESSAGE`(400)로 통일한다.

| 코드(안)                              | 상황            | HTTP |
|------------------------------------|---------------|------|
| `CHAT_404_ROOM_NOT_FOUND`          | 방 없음          | 404  |
| `CHAT_403_NOT_PARTICIPANT`         | 참여자 아님        | 403  |
| `CHAT_409_ROOM_CLOSED`             | 종료된 방에 송신     | 409  |
| `CHAT_400_INVALID_MESSAGE`         | 빈 본문/타입 오류    | 400  |
| `CHAT_400_CANNOT_OPEN_SELF_ROOM`   | 동일 회원으로 방 개설 시도 | 400  |
| `CHAT_400_INVALID_ROOM_PARTICIPANTS` | 참여자 정보 오류(null 등) | 400  |
| `CHAT_409_PROGRESS_ALREADY_AGREED` | 이미 동의한 단계 재동의 | 409  |
| `CHAT_409_ROOM_CREATION_CONFLICT`  | 동시 매칭으로 방 개설 경합 | 409  |

---

## 11. 결정 완료 / 남은 항목

이전 TODO는 모두 결정되어 본문에 반영되었다.

| # | 항목            | 결정                                                               |
|---|---------------|------------------------------------------------------------------|
| 1 | WebSocket 의존성 | ✅ `spring-boot-starter-websocket` 추가 완료(`build.gradle.kts`)      |
| 2 | 중복 방 생성       | ✅ `ACTIVE` 방 중복 금지, `CLOSED` 이후 새 방 허용 (서비스 계층)                  |
| 3 | 읽음 처리 채널      | ✅ WebSocket 우선                                                   |
| 4 | 안읽음 카운트       | ✅ 초기 DB count → 트래픽 증가 시 Redis 전환                                |
| 5 | 사진 단계 연계      | ✅ Chat은 단계만 관리, 사진 공개(블러 해제)는 프로필/회원 도메인                         |
| 6 | SockJS        | ✅ 초기 미사용(순수 WebSocket)                                           |
| 7 | 브로커 확장        | ✅ 1) Simple Broker → 2) Redis Pub/Sub → 3) RabbitMQ 단계 전략 (§6-5) |
| 8 | `SYSTEM` 메시지  | ✅ `ChatMessageType.SYSTEM` 추가 완료                                 |
| 9 | 단계 동의 취소      | ✅ 되돌리기 비허용(단조 증가)                                                |

### 구현 시 남은 검토 항목

- ✅ **구독 시 참여자 검증** 인터셉터 구현(§7-2) — `StompAuthChannelInterceptor`의 `SUBSCRIBE` 처리로 완료.
- ⏳ **연결 도중 토큰 만료** 시 재연결 유도 정책(§6-3) — CONNECT 거절까지만 구현, 세부 정책은 후속.
- ⏳ **오프라인 상대 알림** 생성(Notification 도메인 연동, §7-3) — 후속.
- ⏳ **수평 확장 시 브로커 전환** — 현재 단일 인스턴스 in-memory Simple 브로커(§6-5 1단계), 2대 이상 확장 직전 Redis Pub/Sub로 전환.

---

## 부록: 관련 코드 위치

- 엔티티: `src/main/java/com/blursome/blursome/chat/domain/`
- 아키텍처 규칙: `docs/ARCHITECTURE.md`
- 도메인 모델 요약: `docs/ARCHITECTURE.md § 2 (채팅)` — 본 문서와 동기화 유지
