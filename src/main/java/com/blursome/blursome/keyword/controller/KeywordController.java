package com.blursome.blursome.keyword.controller;

import com.blursome.blursome.global.response.DataResponse;
import com.blursome.blursome.keyword.dto.response.KeywordCategoryResponse;
import com.blursome.blursome.keyword.service.KeywordCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Keyword", description = "관심사 키워드 카탈로그 API")
@RestController
@RequestMapping("/api/keywords")
@RequiredArgsConstructor
public class KeywordController {

  private final KeywordCatalogService keywordCatalogService;

  @Operation(summary = "키워드 카탈로그 조회",
      description = "온보딩 선택 화면용. 카테고리(정렬·필수 여부)와 소속 활성 태그 목록을 반환한다.")
  @GetMapping
  public ResponseEntity<DataResponse<List<KeywordCategoryResponse>>> getCatalog() {
    return ResponseEntity.ok(DataResponse.ok(keywordCatalogService.getCatalog()));
  }
}
