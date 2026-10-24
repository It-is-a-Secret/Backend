package com.blursome.blursome.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.exception.ChatErrorCode;
import com.blursome.blursome.chat.repository.ChatRoomMemberRepository;
import com.blursome.blursome.chat.repository.ChatRoomRepository;
import com.blursome.blursome.chat.service.ChatRoomService;
import com.blursome.blursome.feed.domain.Feed;
import com.blursome.blursome.feed.domain.FeedImage;
import com.blursome.blursome.feed.domain.FeedImageProcessingStatus;
import com.blursome.blursome.feed.dto.request.FeedImageSaveRequest;
import com.blursome.blursome.feed.dto.request.PresignedUrlRequest;
import com.blursome.blursome.feed.dto.request.PresignedUrlRequest.ImageRequest;
import com.blursome.blursome.feed.dto.response.FeedImageResponse;
import com.blursome.blursome.feed.dto.response.MyFeedImagesResponse;
import com.blursome.blursome.feed.dto.response.PresignedUrlResponse;
import com.blursome.blursome.feed.dto.response.PublicFeedImagesResponse;
import com.blursome.blursome.feed.dto.response.RevealedFeedImagesResponse;
import com.blursome.blursome.feed.repository.FeedImageRepository;
import com.blursome.blursome.feed.repository.FeedRepository;
import com.blursome.blursome.feed.scheduler.FeedImageProcessingStatusScheduler;
import com.blursome.blursome.feed.service.FeedImageService;
import com.blursome.blursome.global.exception.BaseException;
import com.blursome.blursome.global.storage.S3Properties;
import com.blursome.blursome.member.domain.Department;
import com.blursome.blursome.member.domain.Gender;
import com.blursome.blursome.member.domain.Mbti;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.member.repository.MemberRepository;
import com.blursome.blursome.support.TestcontainersConfiguration;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * 피드 이미지 블러 파이프라인 #47~#54의 백엔드 종단(end-to-end) 통합 테스트(이슈 #55).
 *
 * <p>실제 MySQL(Testcontainers)·실제 도메인 서비스·실제 스케줄러를 그대로 구동하고, AWS와 닿는 경계인
 * {@link S3Client}/{@link S3Presigner}만 목으로 대체한다. 이렇게 하면 {@code S3StorageService}의 버킷 선택·
 * blur-level 메타데이터 pin·GET 서명·HeadObject 404 처리 같은 #48~#53의 실제 로직은 모두 통과하고, 실제 S3
 * 네트워크 호출만 끊어진다(실 버킷·IAM·Lambda 검증은 #55의 AWS 콘솔 작업과 {@code CHAT_API_TEST_GUIDE.md}의
 * 수동 절차로 별도 수행).
 *
 * <p>검증하는 흐름:
 * <ol>
 *   <li>#49 Presigned PUT 발급 — originals 버킷·blur-level 메타데이터가 서명에 들어가는지</li>
 *   <li>#50 메타데이터 저장(full-replace) — 발급 key로 행 생성, PROCESSING 시작</li>
 *   <li>#51 스케줄러 폴링 — HeadObject 결과로 READY/FAILED 전이</li>
 *   <li>#52 공개 피드 조회 — 전부-READY 게이트 통과 시에만 노출</li>
 *   <li>#53 채팅 단계별 원본 공개 — 진행 단계가 정한 N장만 원본 Presigned GET, 나머지 블러본</li>
 *   <li>#105 기존·신규 혼합 저장 — 유지·교환·삭제·추가·재블러(CopyObject)·롤백·소유권·동시 저장</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class FeedImagePipelineIntegrationTest {

  // 통합 테스트는 롤백되지 않고 member에 유니크 제약(nick_name·provider_id)이 있어 매번 고유 값을 만든다.
  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private S3Properties s3Properties;

  @Autowired
  private FeedImageService feedImageService;

  @Autowired
  private FeedImageProcessingStatusScheduler scheduler;

  @Autowired
  private ChatRoomService chatRoomService;

  @Autowired
  private MemberRepository memberRepository;

  @Autowired
  private FeedRepository feedRepository;

  @Autowired
  private FeedImageRepository feedImageRepository;

  @Autowired
  private ChatRoomRepository chatRoomRepository;

  @Autowired
  private ChatRoomMemberRepository chatRoomMemberRepository;

  @Autowired
  private DataSource dataSource;

  @MockitoBean
  private S3Presigner s3Presigner;

  @MockitoBean
  private S3Client s3Client;

  /** 업로드를 마친 원본 key별 blur-level 메타데이터. 등록하지 않은 원본은 기본값(80)으로 업로드된 것으로 본다. */
  private final Map<String, String> uploadedBlurLevels = new HashMap<>();

  /**
   * 저장 API는 새 사진의 원본을 HeadObject로 확인하므로(#105), 기본으로 originals는 "업로드됨 + 등록한 blur-level",
   * variants는 "블러본 존재"로 응답한다. 테스트가 {@code given(s3Client.headObject(...))}로 덮어쓸 때 인자 없이 한 번
   * 호출되므로 {@code null} 요청도 처리한다.
   */
  @BeforeEach
  void stubUploadedOriginals() {
    willAnswer(invocation -> headResponse(invocation.getArgument(0)))
        .given(s3Client).headObject(any(HeadObjectRequest.class));
  }

  @Test
  @DisplayName("업로드 Presigned PUT → 메타데이터 저장 → 스케줄러 READY → 공개 피드 노출까지 종단으로 동작한다")
  void fullPipeline_uploadToReadyToPublicFeed() {
    Long memberId = persistMemberWithFeed();
    given(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
        .willAnswer(invocation -> presignedPut(presignUrl(
            invocation.<PutObjectPresignRequest>getArgument(0).putObjectRequest())));

    // (#49) 5장에 대한 Presigned PUT 발급 — originals 버킷·blur-level 메타데이터가 서명에 들어가야 한다.
    // 공개 게이트는 정확히 5장 전부 READY를 요구하므로(#72) 종단 시나리오도 5장으로 채운다.
    PresignedUrlResponse issued = feedImageService.issuePresignedUploadUrls(
        memberId,
        new PresignedUrlRequest(List.of(
            new ImageRequest("first.jpg", "image/jpeg", 70),
            new ImageRequest("second.png", "image/png", null),
            new ImageRequest("third.jpg", "image/jpeg", null),
            new ImageRequest("fourth.jpg", "image/jpeg", null),
            new ImageRequest("fifth.jpg", "image/jpeg", null))));
    assertThat(issued.images()).hasSize(5);
    assertThat(issued.images()).allSatisfy(image -> {
      assertThat(image.originalKey()).startsWith("originals/" + memberId + "/");
      assertThat(image.uploadUrl()).isNotBlank();
    });
    String keyA = issued.images().get(0).originalKey();
    String keyB = issued.images().get(1).originalKey();
    // F-2 업로드 결과: 1번은 blur-level 70으로 PUT됐다(나머지는 기본값 80).
    uploadedBlurLevels.put(keyA, "70");

    // 발급된 PUT 서명을 검증한다: 모두 originals 버킷, 앞 두 장의 key/contentType/blur-level(1번 70·2번 기본값 80).
    ArgumentCaptor<PutObjectPresignRequest> putCaptor =
        ArgumentCaptor.forClass(PutObjectPresignRequest.class);
    verify(s3Presigner, times(5)).presignPutObject(putCaptor.capture());
    List<PutObjectRequest> puts = putCaptor.getAllValues().stream()
        .map(PutObjectPresignRequest::putObjectRequest)
        .toList();
    assertThat(puts).allSatisfy(put ->
        assertThat(put.bucket()).isEqualTo(s3Properties.originalsBucket()));
    assertThat(puts.get(0).key()).isEqualTo(keyA);
    assertThat(puts.get(0).contentType()).isEqualTo("image/jpeg");
    assertThat(puts.get(0).metadata()).containsEntry("blur-level", "70");
    assertThat(puts.get(1).key()).isEqualTo(keyB);
    assertThat(puts.get(1).contentType()).isEqualTo("image/png");
    assertThat(puts.get(1).metadata())
        .containsEntry("blur-level", String.valueOf(FeedImage.DEFAULT_BLUR_LEVEL));

    // (#50) 발급받은 원본 key로 메타데이터 저장(full-replace) — 다섯 행이 PROCESSING으로 생성된다.
    List<FeedImageSaveRequest.Image> toSave = new ArrayList<>();
    for (int i = 0; i < issued.images().size(); i++) {
      toSave.add(FeedImageSaveRequest.Image.upload(issued.images().get(i).originalKey(), i + 1,
          i == 0 ? 70 : null));
    }
    FeedImageResponse saved = feedImageService.replaceImages(
        memberId, new FeedImageSaveRequest(toSave));
    assertThat(saved.images())
        .extracting(FeedImageResponse.Image::processingStatus)
        .containsOnly(FeedImageProcessingStatus.PROCESSING);

    // (#72) 아직 블러본이 READY가 아니므로 공개 피드는 정확히-5장-전부-READY 게이트에 막혀 404다.
    Long feedId = feedRepository.findByMemberId(memberId).orElseThrow().getId();
    assertThatThrownBy(() -> feedImageService.getPublicFeedImages(feedId))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_404_NOT_FOUND");

    // (#51) 블러본이 생겼다고 가정(HeadObject 200)하고, 폴링 대상이 되도록 처리 시작 시각을 30초 이전으로 당긴 뒤 스케줄러 실행.
    given(s3Client.headObject(any(HeadObjectRequest.class)))
        .willReturn(HeadObjectResponse.builder().build());
    backdateProcessingStart(feedId, LocalDateTime.now().minusMinutes(1));
    scheduler.pollProcessingStatus();
    assertThat(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(feedId))
        .extracting(FeedImage::getProcessingStatus)
        .containsOnly(FeedImageProcessingStatus.READY);

    // (#72) 정확히 5장 전부 READY가 되면 공개 피드가 displayOrder 순서로 블러본 URL을 노출한다.
    PublicFeedImagesResponse publicFeed = feedImageService.getPublicFeedImages(feedId);
    assertThat(publicFeed.images())
        .extracting(PublicFeedImagesResponse.Image::displayOrder)
        .containsExactly(1, 2, 3, 4, 5);
    assertThat(publicFeed.images())
        .allSatisfy(image -> assertThat(image.variantUrl()).contains("variants/"));
  }

  @Test
  @DisplayName("블러본이 처리 시작 후 제한 시간(5분)까지 안 보이면 스케줄러가 FAILED로 전이하고 본인 조회는 재업로드를 안내한다")
  void scheduler_marksFailed_whenVariantMissingAfterTimeout() {
    Long memberId = persistMemberWithFeed();
    Long feedId = saveProcessingImages(memberId, "k1.jpg", "k2.jpg");

    // HeadObject가 404(객체 없음)이고 처리 시작 후 5분을 넘겼으면 FAILED.
    given(s3Client.headObject(any(HeadObjectRequest.class)))
        .willThrow((S3Exception) S3Exception.builder().statusCode(404).build());
    backdateProcessingStart(feedId, LocalDateTime.now().minusMinutes(6));
    scheduler.pollProcessingStatus();

    assertThat(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(feedId))
        .extracting(FeedImage::getProcessingStatus)
        .containsOnly(FeedImageProcessingStatus.FAILED);

    // 본인 관리 조회는 FAILED를 그대로 내리고 재업로드를 안내한다(#52).
    MyFeedImagesResponse mine = feedImageService.getMyFeedImages(memberId);
    assertThat(mine.reuploadRequired()).isTrue();
    // 공개 피드는 FAILED가 있어 비노출(#52).
    assertThatThrownBy(() -> feedImageService.getPublicFeedImages(feedId))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_404_NOT_FOUND");
  }

  @Test
  @DisplayName("채팅 진행 단계가 오르면 상대 원본이 displayOrder 순서로 1장씩 Presigned GET으로 공개되고 나머지는 블러본이다")
  void chatRevealsPartnerOriginalsByProgress() {
    // A는 READY 사진 3장을 가진 피드 소유자, B는 채팅 상대(A의 사진을 단계별로 보게 된다).
    Long ownerId = persistMemberWithFeed();
    Long feedId = saveProcessingImages(ownerId, "p1.jpg", "p2.jpg", "p3.jpg");
    markAllReady(feedId);
    Long viewerId = persistMember();
    Long roomId = persistRoom(ownerId, viewerId);

    given(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
        .willAnswer(invocation -> presignedGet(presignUrl(
            invocation.<GetObjectPresignRequest>getArgument(0).getObjectRequest())));

    // MATCHED(공개 0장): 전부 블러본, 원본 GET 발급은 한 번도 일어나지 않는다.
    RevealedFeedImagesResponse atMatched = chatRoomService.getRevealedImages(roomId, viewerId);
    assertThat(atMatched.images())
        .extracting(RevealedFeedImagesResponse.Image::revealed)
        .containsExactly(false, false, false);
    verify(s3Presigner, never()).presignGetObject(any(GetObjectPresignRequest.class));

    // 방 단계를 STEP_2(공개 2장)로 올려 둔다. 단계 상승 트리거(유효 메시지 누적)는 chat 도메인에서 별도 검증하고,
    // 여기서는 단계에 따른 원본 공개(#53)만 검증하므로 progressStatus를 직접 설정한다.
    setProgressStatus(roomId, "PHOTO_REVEAL_STEP_2");

    // STEP_2: 앞 2장은 원본(revealed), 3번은 블러본. 원본은 originals 버킷 GET으로만 나간다.
    RevealedFeedImagesResponse atStep2 = chatRoomService.getRevealedImages(roomId, viewerId);
    assertThat(atStep2.images())
        .extracting(RevealedFeedImagesResponse.Image::displayOrder)
        .containsExactly(1, 2, 3);
    assertThat(atStep2.images())
        .extracting(RevealedFeedImagesResponse.Image::revealed)
        .containsExactly(true, true, false);

    // 공개된 2장에 대해서만 GET이 발급되고(3번은 미발급), 둘 다 originals 버킷·소유자의 displayOrder 1·2 원본 key여야 한다.
    List<FeedImage> ownerImages = feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(feedId);
    ArgumentCaptor<GetObjectPresignRequest> getCaptor =
        ArgumentCaptor.forClass(GetObjectPresignRequest.class);
    verify(s3Presigner, times(2)).presignGetObject(getCaptor.capture());
    List<GetObjectRequest> gets = getCaptor.getAllValues().stream()
        .map(GetObjectPresignRequest::getObjectRequest)
        .toList();
    assertThat(gets).allSatisfy(get ->
        assertThat(get.bucket()).isEqualTo(s3Properties.originalsBucket()));
    assertThat(gets).extracting(GetObjectRequest::key)
        .containsExactly(
            ownerImages.get(0).getOriginalKey(), ownerImages.get(1).getOriginalKey());
  }

  @Test
  @DisplayName("상대가 나가 종료(CLOSED)된 방에서는 남은 참여자도 원본 단계별 조회가 ROOM_CLOSED로 막힌다")
  void chatReveal_blockedAfterRoomClosed() {
    Long ownerId = persistMemberWithFeed();
    Long feedId = saveProcessingImages(ownerId, "q1.jpg");
    markAllReady(feedId);
    Long viewerId = persistMember();
    Long roomId = persistRoom(ownerId, viewerId);

    // 소유자(A)가 나가면 1:1 방은 종료된다.
    chatRoomService.leaveRoom(roomId, ownerId);

    // 원본 공개는 ACTIVE 방으로 한정 — 비활성(CLOSED)은 원본 미발급 + 기존 계약대로 409.
    assertThatThrownBy(() -> chatRoomService.getRevealedImages(roomId, viewerId))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_CLOSED.getCode());
    verify(s3Presigner, never()).presignGetObject(any(GetObjectPresignRequest.class));
  }

  @Test
  @DisplayName("유지·순서 교환·삭제·추가·재블러를 한 요청에 섞어도 최종 목록이 정확하고 재처리 행은 새 key로 READY가 된다(#105)")
  void mixedSave_keepsSwapsDeletesAddsAndReprocesses() {
    Long memberId = persistMemberWithFeed();
    Long feedId = saveProcessingImages(memberId, "a.png", "b.jpg", "c.jpg");
    markAllReady(feedId);
    List<FeedImage> before = feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(feedId);
    FeedImage a = before.get(0);
    FeedImage b = before.get(1);
    FeedImage c = before.get(2);
    String newKey = ownedKey(memberId, "d.png");

    // 재처리 원본 복사: HeadObject로 Content-Type을 읽고 CopyObject로 새 key에 쓴다.
    given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(HeadObjectResponse.builder()
        .contentType("image/png")
        .metadata(Map.of("blur-level", "80"))
        .build());

    // b를 1번으로, a를 2번으로 교환하면서 a만 95로 재블러, c는 삭제, 새 사진 d를 3번에 추가한다.
    FeedImageResponse saved = feedImageService.replaceImages(memberId, new FeedImageSaveRequest(List.of(
        FeedImageSaveRequest.Image.existing(b.getId(), 1, null),
        FeedImageSaveRequest.Image.existing(a.getId(), 2, 95),
        FeedImageSaveRequest.Image.upload(newKey, 3, null))));

    assertThat(saved.images())
        .extracting(FeedImageResponse.Image::displayOrder)
        .containsExactly(1, 2, 3);
    assertThat(saved.images().get(0).id()).isEqualTo(b.getId());
    assertThat(saved.images().get(0).processingStatus()).isEqualTo(FeedImageProcessingStatus.READY);
    assertThat(saved.images().get(1).id()).isEqualTo(a.getId());
    assertThat(saved.images().get(1).blurLevel()).isEqualTo(95);
    assertThat(saved.images().get(1).processingStatus())
        .isEqualTo(FeedImageProcessingStatus.PROCESSING);
    assertThat(saved.images().get(1).variantUrl()).doesNotContain(a.getVariantKey());
    assertThat(saved.images().get(2).processingStatus())
        .isEqualTo(FeedImageProcessingStatus.PROCESSING);
    assertThat(feedImageRepository.findById(c.getId())).isEmpty();

    // DB 상태: b는 key·상태 그대로, a는 같은 id에 새 original/variant key.
    FeedImage reloadedB = findImage(b.getId());
    assertThat(reloadedB.getOriginalKey()).isEqualTo(b.getOriginalKey());
    assertThat(reloadedB.getProcessingStatus()).isEqualTo(FeedImageProcessingStatus.READY);
    FeedImage reloadedA = findImage(a.getId());
    assertThat(reloadedA.getOriginalKey())
        .startsWith("originals/" + memberId + "/")
        .endsWith(".png")
        .isNotEqualTo(a.getOriginalKey());
    assertThat(reloadedA.getVariantKey()).isNotEqualTo(a.getVariantKey());

    // CopyObject: originals 버킷 안 server-side 복사, 메타데이터 REPLACE로 새 blur-level과 기존 Content-Type 기록.
    ArgumentCaptor<CopyObjectRequest> copyCaptor = ArgumentCaptor.forClass(CopyObjectRequest.class);
    verify(s3Client, times(1)).copyObject(copyCaptor.capture());
    CopyObjectRequest copy = copyCaptor.getValue();
    assertThat(copy.sourceBucket()).isEqualTo(s3Properties.originalsBucket());
    assertThat(copy.destinationBucket()).isEqualTo(s3Properties.originalsBucket());
    assertThat(copy.sourceKey()).isEqualTo(a.getOriginalKey());
    assertThat(copy.destinationKey()).isEqualTo(reloadedA.getOriginalKey());
    assertThat(copy.metadataDirective()).isEqualTo(MetadataDirective.REPLACE);
    assertThat(copy.contentType()).isEqualTo("image/png");
    assertThat(copy.metadata()).containsEntry("blur-level", "95");

    // 생성은 오래전이어도 재처리를 방금 시작했으므로, 블러본이 아직 없다고 FAILED가 되지 않는다.
    backdateCreatedAtOnly(feedId, LocalDateTime.now().minusMinutes(10));
    given(s3Client.headObject(any(HeadObjectRequest.class)))
        .willThrow((S3Exception) S3Exception.builder().statusCode(404).build());
    scheduler.pollProcessingStatus();
    assertThat(findImage(a.getId()).getProcessingStatus())
        .isEqualTo(FeedImageProcessingStatus.PROCESSING);

    // 처리 시작 30초 이후 새 블러본이 확인되면 READY가 된다.
    backdateProcessingStart(feedId, LocalDateTime.now().minusMinutes(1));
    willReturn(HeadObjectResponse.builder().build())
        .given(s3Client).headObject(any(HeadObjectRequest.class));
    scheduler.pollProcessingStatus();
    assertThat(findImage(a.getId()).getProcessingStatus())
        .isEqualTo(FeedImageProcessingStatus.READY);
  }

  @Test
  @DisplayName("S3 복사가 실패하면 삭제·재정렬·추가까지 모두 롤백되어 기존 목록이 그대로 남는다(#105)")
  void mixedSave_rollsBackWhenCopyFails() {
    Long memberId = persistMemberWithFeed();
    Long feedId = saveProcessingImages(memberId, "a.png", "b.jpg");
    markAllReady(feedId);
    List<FeedImage> before = feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(feedId);
    String reprocessSource = before.get(1).getOriginalKey();
    // 새 사진 원본 확인은 통과하고, 재블러 대상 원본 조회만 권한 오류로 실패시킨다.
    willAnswer(invocation -> {
      HeadObjectRequest head = invocation.getArgument(0);
      if (head != null && reprocessSource.equals(head.key())) {
        throw S3Exception.builder().statusCode(403).build();
      }
      return headResponse(head);
    }).given(s3Client).headObject(any(HeadObjectRequest.class));

    assertThatThrownBy(() -> feedImageService.replaceImages(memberId, new FeedImageSaveRequest(List.of(
        FeedImageSaveRequest.Image.existing(before.get(1).getId(), 1, 60),
        FeedImageSaveRequest.Image.upload(ownedKey(memberId, "c.jpg"), 2, null)))))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "SERVER_ERROR_500_INTERNAL_SERVER_ERROR");

    assertThat(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(feedId))
        .extracting(FeedImage::getId, FeedImage::getOriginalKey, FeedImage::getBlurLevel,
            FeedImage::getProcessingStatus)
        .containsExactly(
            tuple(before.get(0).getId(), before.get(0).getOriginalKey(), 80,
                FeedImageProcessingStatus.READY),
            tuple(before.get(1).getId(), before.get(1).getOriginalKey(), 80,
                FeedImageProcessingStatus.READY));
  }

  @Test
  @DisplayName("스케줄러가 블러본을 확인하는 사이 저장 API가 재블러·재정렬하면 이전 세대 결과로 새 세대나 순서를 덮어쓰지 않는다(#105)")
  void scheduler_doesNotOverwriteImageChangedDuringCheck() {
    Long memberId = persistMemberWithFeed();
    Long feedId = saveProcessingImages(memberId, "a.png", "b.jpg");
    backdateProcessingStart(feedId, LocalDateTime.now().minusMinutes(1));
    List<FeedImage> before = feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(feedId);
    FeedImage a = before.get(0);
    FeedImage b = before.get(1);

    // 스케줄러가 a의 이전 블러본을 HeadObject하는 순간, 저장 API가 a를 95로 재블러하고 a↔b 순서를 바꿔 커밋한다.
    AtomicBoolean saveDone = new AtomicBoolean();
    willAnswer(invocation -> {
      HeadObjectRequest head = invocation.getArgument(0);
      if (head != null && a.getVariantKey().equals(head.key()) && saveDone.compareAndSet(false, true)) {
        feedImageService.replaceImages(memberId, new FeedImageSaveRequest(List.of(
            FeedImageSaveRequest.Image.existing(b.getId(), 1, null),
            FeedImageSaveRequest.Image.existing(a.getId(), 2, 95))));
      }
      return headResponse(head);
    }).given(s3Client).headObject(any(HeadObjectRequest.class));

    scheduler.pollProcessingStatus();

    assertThat(saveDone).isTrue();
    // a: 이전 블러본이 있다는 확인 결과는 새 세대에 적용되지 않는다. 재처리 결과(key·blur·순서·PROCESSING)가 그대로다.
    FeedImage reloadedA = findImage(a.getId());
    assertThat(reloadedA.getVariantKey()).isNotEqualTo(a.getVariantKey());
    assertThat(reloadedA.getBlurLevel()).isEqualTo(95);
    assertThat(reloadedA.getDisplayOrder()).isEqualTo(2);
    assertThat(reloadedA.getProcessingStatus()).isEqualTo(FeedImageProcessingStatus.PROCESSING);
    // b: 같은 세대라 READY로 전이되지만, 스케줄러가 읽어 둔 이전 순서(2)로 되돌리지 않는다.
    FeedImage reloadedB = findImage(b.getId());
    assertThat(reloadedB.getProcessingStatus()).isEqualTo(FeedImageProcessingStatus.READY);
    assertThat(reloadedB.getDisplayOrder()).isEqualTo(1);
    assertThat(reloadedB.getVariantKey()).isEqualTo(b.getVariantKey());
  }

  @Test
  @DisplayName("새 사진의 blurLevel이 실제 업로드된 원본 메타데이터와 다르면 거부하고, 생략하면 업로드 값을 기록한다(#105)")
  void save_verifiesUploadedBlurLevel() {
    Long memberId = persistMemberWithFeed();
    String mismatched = ownedKey(memberId, "a.jpg");
    String omitted = ownedKey(memberId, "b.jpg");
    uploadedBlurLevels.put(mismatched, "50");
    uploadedBlurLevels.put(omitted, "60");

    assertThatThrownBy(() -> feedImageService.replaceImages(memberId, new FeedImageSaveRequest(List.of(
        FeedImageSaveRequest.Image.upload(mismatched, 1, 100)))))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_IMAGE_400_BLUR_LEVEL_MISMATCH");

    FeedImageResponse saved = feedImageService.replaceImages(memberId, new FeedImageSaveRequest(List.of(
        FeedImageSaveRequest.Image.upload(omitted, 1, null))));
    assertThat(saved.images()).extracting(FeedImageResponse.Image::blurLevel).containsExactly(60);
  }

  @Test
  @DisplayName("다른 회원의 사진 id를 보내면 404이고 두 피드 모두 바뀌지 않는다(#105)")
  void mixedSave_rejectsForeignImageId() {
    Long ownerId = persistMemberWithFeed();
    Long otherId = persistMemberWithFeed();
    Long ownerFeedId = saveProcessingImages(ownerId, "a.png");
    Long otherFeedId = saveProcessingImages(otherId, "x.png");
    Long foreignImageId =
        feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(otherFeedId).get(0).getId();

    assertThatThrownBy(() -> feedImageService.replaceImages(ownerId, new FeedImageSaveRequest(List.of(
        FeedImageSaveRequest.Image.existing(foreignImageId, 1, null)))))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", "FEED_IMAGE_404_NOT_FOUND");

    assertThat(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(ownerFeedId)).hasSize(1);
    assertThat(feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(otherFeedId))
        .extracting(FeedImage::getId)
        .containsExactly(foreignImageId);
  }

  @Test
  @DisplayName("같은 회원의 저장 요청이 동시에 와도 한 요청의 목록만 온전히 남는다(#105)")
  void concurrentSaves_doNotMixResults() throws Exception {
    Long memberId = persistMemberWithFeed();
    Long feedId = saveProcessingImages(memberId, "a.png", "b.jpg");
    List<Long> existingIds = feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(feedId).stream()
        .map(FeedImage::getId)
        .toList();

    // 요청 1: 기존 2장을 교환 + 새 사진 1장. 요청 2: 기존을 모두 지우고 새 사진 3장.
    List<String> firstUploads = List.of(ownedKey(memberId, "c.jpg"));
    List<String> secondUploads = List.of(
        ownedKey(memberId, "x.jpg"), ownedKey(memberId, "y.jpg"), ownedKey(memberId, "z.jpg"));
    FeedImageSaveRequest first = new FeedImageSaveRequest(List.of(
        FeedImageSaveRequest.Image.existing(existingIds.get(1), 1, null),
        FeedImageSaveRequest.Image.existing(existingIds.get(0), 2, null),
        FeedImageSaveRequest.Image.upload(firstUploads.get(0), 3, null)));
    FeedImageSaveRequest second = new FeedImageSaveRequest(List.of(
        FeedImageSaveRequest.Image.upload(secondUploads.get(0), 1, null),
        FeedImageSaveRequest.Image.upload(secondUploads.get(1), 2, null),
        FeedImageSaveRequest.Image.upload(secondUploads.get(2), 3, null)));

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<?> firstResult = executor.submit(() -> {
        start.await();
        return feedImageService.replaceImages(memberId, first);
      });
      Future<?> secondResult = executor.submit(() -> {
        start.await();
        return feedImageService.replaceImages(memberId, second);
      });
      start.countDown();

      // 요청 2가 먼저 끝나면 요청 1의 기존 id가 사라져 404가 날 수 있다. 그 외 실패는 허용하지 않는다.
      List<Throwable> failures = new ArrayList<>();
      for (Future<?> result : List.of(firstResult, secondResult)) {
        try {
          result.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
          failures.add(e.getCause());
        }
      }
      assertThat(failures).allSatisfy(failure -> assertThat(failure)
          .isInstanceOf(BaseException.class)
          .hasFieldOrPropertyWithValue("code", "FEED_IMAGE_404_NOT_FOUND"));
    } finally {
      executor.shutdownNow();
    }

    List<FeedImage> finalImages = feedImageRepository.findByFeedIdOrderByDisplayOrderAsc(feedId);
    List<String> finalKeys = finalImages.stream().map(FeedImage::getOriginalKey).toList();
    boolean isFirstResult = finalImages.size() == 3
        && finalImages.get(0).getId().equals(existingIds.get(1))
        && finalImages.get(1).getId().equals(existingIds.get(0))
        && finalKeys.get(2).equals(firstUploads.get(0));
    boolean isSecondResult = finalKeys.equals(secondUploads);
    assertThat(isFirstResult || isSecondResult)
        .as("최종 목록은 한 요청의 결과와 정확히 같아야 한다: %s", finalKeys)
        .isTrue();
  }

  // ---------- helpers ----------

  /** 방의 사진 공개 단계를 직접 설정한다(단계별 원본 공개 검증용 — 단계 상승 트리거는 chat 도메인 테스트가 담당). */
  private void setProgressStatus(Long roomId, String progressStatus) {
    new JdbcTemplate(dataSource).update(
        "UPDATE chat_room SET progress_status = ? WHERE id = ?", progressStatus, roomId);
  }

  /** PROCESSING 상태로 사진을 저장하고 피드 id를 돌려준다(원본 key는 회원 소유 형식이어야 한다). */
  private Long saveProcessingImages(Long memberId, String... fileTags) {
    List<FeedImageSaveRequest.Image> images = new ArrayList<>();
    for (int i = 0; i < fileTags.length; i++) {
      images.add(FeedImageSaveRequest.Image.upload(ownedKey(memberId, fileTags[i]), i + 1, 80));
    }
    feedImageService.replaceImages(memberId, new FeedImageSaveRequest(images));
    return feedRepository.findByMemberId(memberId).orElseThrow().getId();
  }

  /** 피드의 모든 이미지를 READY로 전이시킨다(공개/단계 조회 전제 충족용). */
  private void markAllReady(Long feedId) {
    new JdbcTemplate(dataSource).update(
        "UPDATE feed_image SET processing_status = 'READY' WHERE feed_id = ?", feedId);
  }

  /** 스케줄러 폴링 대상이 되도록 처리 시작 시각과 created_at(updatable=false)을 JDBC로 함께 당긴다. */
  private void backdateProcessingStart(Long feedId, LocalDateTime startedAt) {
    new JdbcTemplate(dataSource).update(
        "UPDATE feed_image SET created_at = ?, processing_started_at = ? WHERE feed_id = ?",
        Timestamp.valueOf(startedAt), Timestamp.valueOf(startedAt), feedId);
  }

  /** 재처리 직후에도 FAILED가 되지 않음을 보이려고 created_at만 과거로 당긴다. */
  private void backdateCreatedAtOnly(Long feedId, LocalDateTime createdAt) {
    new JdbcTemplate(dataSource).update(
        "UPDATE feed_image SET created_at = ? WHERE feed_id = ?",
        Timestamp.valueOf(createdAt), feedId);
  }

  /** originals는 업로드된 원본(등록한 blur-level, 기본 80), variants는 존재하는 블러본으로 응답한다. */
  private HeadObjectResponse headResponse(HeadObjectRequest request) {
    if (request == null || !s3Properties.originalsBucket().equals(request.bucket())) {
      return HeadObjectResponse.builder().build();
    }
    return HeadObjectResponse.builder()
        .contentType("image/jpeg")
        .metadata(Map.of("blur-level", uploadedBlurLevels.getOrDefault(request.key(), "80")))
        .build();
  }

  private FeedImage findImage(Long imageId) {
    return feedImageRepository.findById(imageId).orElseThrow();
  }

  /** 회원 소유 형식의 원본 key. {@code keyGenerator.isOwnedOriginalKey} 통과를 위해 uuid 형식을 따른다. */
  private String ownedKey(Long memberId, String tag) {
    String uuid = "00000000-0000-0000-0000-%012d".formatted(SEQ.incrementAndGet());
    return "originals/" + memberId + "/" + uuid + "." + tag.substring(tag.lastIndexOf('.') + 1);
  }

  private Long persistMember() {
    String unique = "m-" + SEQ.incrementAndGet() + "-" + System.nanoTime();
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, unique, "name", unique + "@example.com", null);
    ReflectionTestUtils.setField(member, "nickName", "닉네임-" + unique);
    return memberRepository.save(member).getId();
  }

  /** 회원과 그 회원의 피드(1:1)를 함께 영속화하고 회원 id를 돌려준다. */
  private Long persistMemberWithFeed() {
    String unique = "m-" + SEQ.incrementAndGet() + "-" + System.nanoTime();
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, unique, "name", unique + "@example.com", null);
    ReflectionTestUtils.setField(member, "nickName", "닉네임-" + unique);
    Member saved = memberRepository.save(member);
    feedRepository.save(Feed.createOnOnboarding(
        saved, Gender.MALE, 2000, Department.ENGLISH, Mbti.ISTJ));
    return saved.getId();
  }

  private Long persistRoom(Long memberAId, Long memberBId) {
    Member a = memberRepository.findById(memberAId).orElseThrow();
    Member b = memberRepository.findById(memberBId).orElseThrow();
    ChatRoom room = chatRoomRepository.save(ChatRoom.createOnMatched(memberAId, memberBId));
    chatRoomMemberRepository.save(ChatRoomMember.join(room, a));
    chatRoomMemberRepository.save(ChatRoomMember.join(room, b));
    return room.getId();
  }

  // ----- S3 SDK 목 헬퍼 -----
  // 발급 요청 자체(버킷·키·메타데이터)의 정확성은 각 테스트에서 ArgumentCaptor로 검증하고,
  // 여기서는 요청에서 버킷/키를 뽑아 그럴듯한 가짜 Presigned URL만 만들어 돌려준다.

  private String presignUrl(PutObjectRequest put) {
    return "https://%s.s3.amazonaws.com/%s".formatted(put.bucket(), put.key());
  }

  private String presignUrl(GetObjectRequest get) {
    return "https://%s.s3.amazonaws.com/%s".formatted(get.bucket(), get.key());
  }

  private PresignedPutObjectRequest presignedPut(String url) {
    return PresignedPutObjectRequest.builder()
        .expiration(Instant.now().plusSeconds(300))
        .isBrowserExecutable(false)
        .signedHeaders(Map.of("host", List.of(URI.create(url).getHost())))
        .httpRequest(SdkHttpFullRequest.builder()
            .method(SdkHttpMethod.PUT).uri(URI.create(url)).build())
        .build();
  }

  private PresignedGetObjectRequest presignedGet(String url) {
    return PresignedGetObjectRequest.builder()
        .expiration(Instant.now().plusSeconds(300))
        .isBrowserExecutable(true)
        .signedHeaders(Map.of("host", List.of(URI.create(url).getHost())))
        .httpRequest(SdkHttpFullRequest.builder()
            .method(SdkHttpMethod.GET).uri(URI.create(url)).build())
        .build();
  }
}
