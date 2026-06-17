package com.blursome.blursome.member.dto.response;

import com.blursome.blursome.member.domain.InterestCategory;
import com.blursome.blursome.member.domain.InterestCategoryType;

/** 회원 관심사 한 건 응답. */
public record InterestResponse(
    InterestCategoryType name,
    String label,
    String description
) {

  public static InterestResponse from(InterestCategory interest) {
    return new InterestResponse(
        interest.getName(),
        interest.getName().getLabel(),
        interest.getDescription()
    );
  }
}
