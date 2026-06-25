package com.blursome.blursome.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
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
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
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
      toSave.add(new FeedImageSaveRequest.Image(issued.images().get(i).originalKey(), i + 1,
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

    // (#51) 블러본이 생겼다고 가정(HeadObject 200)하고, 폴링 대상이 되도록 created_at을 30초 이전으로 당긴 뒤 스케줄러 실행.
    given(s3Client.headObject(any(HeadObjectRequest.class)))
        .willReturn(HeadObjectResponse.builder().build());
    backdateCreatedAt(feedId, LocalDateTime.now().minusMinutes(1));
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
  @DisplayName("블러본이 제한 시간(5분)까지 안 보이면 스케줄러가 FAILED로 전이하고 본인 조회는 재업로드를 안내한다")
  void scheduler_marksFailed_whenVariantMissingAfterTimeout() {
    Long memberId = persistMemberWithFeed();
    Long feedId = saveProcessingImages(memberId, "k1.jpg", "k2.jpg");

    // HeadObject가 404(객체 없음)이고 created_at이 5분을 넘겼으면 FAILED.
    given(s3Client.headObject(any(HeadObjectRequest.class)))
        .willThrow((S3Exception) S3Exception.builder().statusCode(404).build());
    backdateCreatedAt(feedId, LocalDateTime.now().minusMinutes(6));
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

    // 양쪽 동의를 두 번 반복해 단계를 STEP_2(공개 2장)까지 올린다.
    advanceProgressOneStep(roomId, ownerId, viewerId); // → STEP_1
    advanceProgressOneStep(roomId, ownerId, viewerId); // → STEP_2

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

    assertThatThrownBy(() -> chatRoomService.getRevealedImages(roomId, viewerId))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ChatErrorCode.ROOM_CLOSED.getCode());
  }

  // ---------- helpers ----------

  /** 한 단계 진행: 양쪽이 다음 단계에 동의하면 방 단계가 한 칸 오른다. */
  private void advanceProgressOneStep(Long roomId, Long memberAId, Long memberBId) {
    chatRoomService.agreeProgress(roomId, memberAId);
    chatRoomService.agreeProgress(roomId, memberBId);
  }

  /** PROCESSING 상태로 사진을 저장하고 피드 id를 돌려준다(원본 key는 회원 소유 형식이어야 한다). */
  private Long saveProcessingImages(Long memberId, String... fileTags) {
    List<FeedImageSaveRequest.Image> images = new ArrayList<>();
    for (int i = 0; i < fileTags.length; i++) {
      images.add(new FeedImageSaveRequest.Image(ownedKey(memberId, fileTags[i]), i + 1, 80));
    }
    feedImageService.replaceImages(memberId, new FeedImageSaveRequest(images));
    return feedRepository.findByMemberId(memberId).orElseThrow().getId();
  }

  /** 피드의 모든 이미지를 READY로 전이시킨다(공개/단계 조회 전제 충족용). */
  private void markAllReady(Long feedId) {
    new JdbcTemplate(dataSource).update(
        "UPDATE feed_image SET processing_status = 'READY' WHERE feed_id = ?", feedId);
  }

  /** created_at은 updatable=false라 더티 체킹으로 못 바꾸므로, 스케줄러 폴링 대상이 되도록 JDBC로 직접 당긴다. */
  private void backdateCreatedAt(Long feedId, LocalDateTime createdAt) {
    new JdbcTemplate(dataSource).update(
        "UPDATE feed_image SET created_at = ? WHERE feed_id = ?",
        Timestamp.valueOf(createdAt), feedId);
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
