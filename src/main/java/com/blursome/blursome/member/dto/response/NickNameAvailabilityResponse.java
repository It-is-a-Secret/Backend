package com.blursome.blursome.member.dto.response;

/**
 * 닉네임 사용 가능 여부(#95). 본인의 현재 닉네임은 사용 가능({@code available=true})으로 본다.
 *
 * <p>조회 시점의 결과일 뿐이며, 최종 저장 시 서버가 다시 검증한다.
 */
public record NickNameAvailabilityResponse(
    String nickName,
    boolean available
) {

}
