package com.blursome.blursome.global.storage;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 피드 이미지 S3 객체 키 규칙. 원본과 블러본의 키를 결정론적으로 대응시킨다.
 *
 * <pre>
 *   originals/{memberId}/{uuid}.{ext}    ← 비공개 원본 (프론트 PUT 대상)
 *   variants/{memberId}/{uuid}.jpg       ← 공개 블러본 (Lambda 출력, 피드 노출)
 * </pre>
 *
 * <p>블러본 키를 원본 uuid 기반 규칙으로 정하므로, 백엔드는 Lambda의 통지 없이도 원본 키만으로
 * 블러본 키를 계산해 DB에 기록할 수 있다(B 방식). (설계: {@code FEED_IMAGE_BLUR_PIPELINE.md} §2 키 레이아웃)
 */
@Component
public class S3ObjectKeyGenerator {

  private static final String ORIGINALS_PREFIX = "originals/";
  private static final String VARIANTS_PREFIX = "variants/";
  /** Lambda가 블러본을 항상 jpg로 출력하므로 블러본 확장자는 jpg로 고정한다. */
  private static final String VARIANT_EXTENSION = "jpg";

  /** {@code generateOriginalKey}가 만드는 파일명({@code {uuid}.{ext}}) 형식. 경로 구분자({@code /})는 허용하지 않는다. */
  private static final Pattern UUID_FILE_NAME = Pattern.compile(
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.[A-Za-z0-9]+");

  /**
   * 새 원본 객체 키를 생성한다. uuid는 매 호출마다 새로 발급된다.
   *
   * @param memberId 소유 회원 id
   * @param extension 원본 확장자(점 제외, 예: {@code jpg}, {@code png})
   * @return {@code originals/{memberId}/{uuid}.{ext}}
   */
  public String generateOriginalKey(Long memberId, String extension) {
    if (memberId == null) {
      throw new IllegalArgumentException("memberId는 필수입니다.");
    }
    String normalizedExt = normalizeExtension(extension);
    return ORIGINALS_PREFIX + memberId + "/" + UUID.randomUUID() + "." + normalizedExt;
  }

  /**
   * 주어진 원본 키가 {@code memberId} 소유의 정상 형식({@code originals/{memberId}/{uuid}.{ext}})인지 검증한다.
   * 메타데이터 저장 시 클라이언트가 보낸 key를 신뢰하기 전에 호출해, 다른 회원 prefix나 임의 경로·형식 위반을
   * 걸러낸다(IDOR·깨진 key 차단). 실제 S3 객체 존재 여부는 검증하지 않는다(블러본 존재는 스케줄러가 추적).
   *
   * @param memberId 인증된 회원 id
   * @param originalKey 클라이언트가 제출한 원본 객체 key
   * @return 형식과 소유자가 모두 일치하면 {@code true}
   */
  public boolean isOwnedOriginalKey(Long memberId, String originalKey) {
    if (memberId == null || originalKey == null) {
      return false;
    }
    String expectedPrefix = ORIGINALS_PREFIX + memberId + "/";
    if (!originalKey.startsWith(expectedPrefix)) {
      return false;
    }
    String fileName = originalKey.substring(expectedPrefix.length());
    return UUID_FILE_NAME.matcher(fileName).matches();
  }

  /**
   * 원본 키로부터 대응하는 블러본 키를 계산한다(결정론적). 확장자는 jpg로 고정된다.
   *
   * @param originalKey {@code originals/{memberId}/{uuid}.{ext}}
   * @return {@code variants/{memberId}/{uuid}.jpg}
   * @throws IllegalArgumentException 원본 키 형식이 아닌 경우
   */
  public String toVariantKey(String originalKey) {
    if (originalKey == null || !originalKey.startsWith(ORIGINALS_PREFIX)) {
      throw new IllegalArgumentException("원본 키 형식이 아닙니다: " + originalKey);
    }
    String relativePath = originalKey.substring(ORIGINALS_PREFIX.length());
    String withoutExtension = stripExtension(relativePath);
    return VARIANTS_PREFIX + withoutExtension + "." + VARIANT_EXTENSION;
  }

  private String normalizeExtension(String extension) {
    if (extension == null || extension.isBlank()) {
      throw new IllegalArgumentException("확장자는 비어 있을 수 없습니다.");
    }
    String trimmed = extension.strip().toLowerCase(Locale.ROOT);
    return trimmed.startsWith(".") ? trimmed.substring(1) : trimmed;
  }

  private String stripExtension(String path) {
    int lastDot = path.lastIndexOf('.');
    if (lastDot <= 0) {
      throw new IllegalArgumentException("확장자가 없는 원본 키입니다: " + path);
    }
    return path.substring(0, lastDot);
  }
}
