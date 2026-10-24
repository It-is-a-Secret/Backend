# 신고 사유 코드 (Report Reason)

신고 요청(`POST /api/reports`)의 `reason`은 화면 신고 항목 7개와 1:1로 대응한다(이슈 #102).

## 1. 화면 항목 ↔ 서버 코드

| 화면 문구 | 서버 코드 | 비고 |
|---|---|---|
| 불쾌한 대화 | `INAPPROPRIATE_CHAT` | 기존 |
| 성적인 내용 | `SEXUAL_HARASSMENT` | 기존 코드명 유지(아래 §2) |
| 만남/약속 강요 | `COERCIVE_MEETING` | 신규 |
| 위험한 사람 | `DANGEROUS_PERSON` | 신규 |
| 금전·개인정보 요구 | `FINANCIAL_OR_PERSONAL_INFO_REQUEST` | 신규 |
| 광고·홍보 | `SPAM` | 기존 |
| 기타 | `ETC` | 기존 |

Swagger(`ReportRequest.reason`)에는 이 7개만 노출된다. 화면 문구는 `ReportReason.label`에 함께 둔다.

## 2. 설계 결정

- **`SEXUAL_HARASSMENT` 유지**: `SEXUAL_CONTENT`로 바꾸면 저장된 행 이관(UPDATE)과 기존 클라이언트 수정이 함께
  필요하다. 의미("성적인 내용")는 `label`로 표현하고 코드명은 유지한다.
- **`HATE_SPEECH`·`PHOTO_THEFT`는 삭제하지 않고 `selectable=false`**: `@Enumerated(STRING)`이라 enum에서 지우면 이 값으로
  저장된 과거 행을 읽을 때 매핑 오류가 난다. enum에는 `@Deprecated`로 남겨 조회는 되게 하고, 신규 신고에서는
  `400 REPORT_400_REASON_NOT_SELECTABLE`로 거절한다.
- **값 이름 변경·삭제 금지, 추가만 허용**: 저장 식별자가 값 이름이기 때문이다.
- **집계 영향 없음**: 임계치 집계(`countDistinctReporters`)는 사유와 무관하게 고유 신고자 수만 센다.

## 3. 오류 응답

| 경우 | 응답 |
|---|---|
| 정의되지 않은 코드(예: `"SEXUAL_CONTENT"`) | `400 CLIENT_ERROR_400_INVALID_REQUEST_BODY` |
| 화면에서 빠진 과거 코드(`HATE_SPEECH`, `PHOTO_THEFT`) | `400 REPORT_400_REASON_NOT_SELECTABLE` |
| `reason` 누락 | `400 CLIENT_ERROR_400_METHOD_ARGUMENT_NOT_VALID` |

## 4. 저장 컬럼 (`report.reason`)

Hibernate 6의 MySQL 방언은 `EnumType.STRING`을 네이티브 `enum('SPAM', ...)` 컬럼으로 만든다. 이 경우 사유를 추가할
때마다 컬럼 정의를 고쳐야 하고, `ddl-auto: update`는 기존 컬럼을 바꾸지 않으므로 누락하면 새 사유의 INSERT가
실패한다(`Data truncated for column 'reason'`). 그래서 `@JdbcTypeCode(SqlTypes.VARCHAR)`로 **`VARCHAR(40)`** 에 저장한다
(가장 긴 코드 34자).

**기존 DB는 1회 수동 변경이 필요하다**(`ddl-auto: update`는 컬럼 타입을 바꾸지 않음). 값은 그대로 보존된다.

```sql
ALTER TABLE report MODIFY COLUMN reason VARCHAR(40) NOT NULL;
```
