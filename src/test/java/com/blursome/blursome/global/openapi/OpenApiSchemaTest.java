package com.blursome.blursome.global.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blursome.blursome.support.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 생성된 OpenAPI 명세({@code /v3/api-docs})가 실제 DTO 구조와 일치하는지 검증한다(#97).
 *
 * <p>springdoc은 클래스 simple name으로 스키마 이름을 정하므로, 여러 DTO의 내부 record가 같은 이름
 * (예: {@code Image})이면 하나의 스키마로 합쳐져 다른 DTO의 필드로 명세가 생성된다. 프론트가 명세로 타입을
 * 생성하므로, 내부 record에는 {@code @Schema(name = ...)}로 고유한 이름을 지정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OpenApiSchemaTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  private JsonNode schemas;

  @BeforeEach
  void setUp() throws Exception {
    String body = mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    schemas = objectMapper.readTree(body).path("components").path("schemas");
  }

  @Test
  @DisplayName("사진 DTO의 내부 Image record는 각자 고유한 스키마로 생성된다")
  void imageSchemas_areNotMerged() {
    assertThat(schemas.has("Image")).isFalse();

    assertImagesItem("FeedImageSaveRequest", "FeedImageSaveItem",
        "objectKey", "displayOrder", "blurLevel");
    assertImagesItem("FeedImageResponse", "FeedImageItem",
        "id", "displayOrder", "variantUrl", "blurLevel", "processingStatus");
    assertImagesItem("MyFeedImagesResponse", "FeedImageItem",
        "id", "displayOrder", "variantUrl", "blurLevel", "processingStatus");
    assertImagesItem("PublicFeedImagesResponse", "PublicFeedImageItem",
        "displayOrder", "variantUrl");
    assertImagesItem("RevealedFeedImagesResponse", "RevealedFeedImageItem",
        "role", "displayOrder", "revealed", "imageUrl");
  }

  /** {@code schemaName.images[]}가 {@code itemSchemaName}을 가리키고, 그 스키마의 필드가 정확히 {@code fields}인지 검증한다. */
  private void assertImagesItem(String schemaName, String itemSchemaName, String... fields) {
    String ref = schemas.path(schemaName).path("properties").path("images").path("items")
        .path("$ref").asText();
    assertThat(ref).as("%s.images[] 참조", schemaName)
        .isEqualTo("#/components/schemas/" + itemSchemaName);

    List<String> actualFields = new ArrayList<>();
    schemas.path(itemSchemaName).path("properties").fieldNames().forEachRemaining(actualFields::add);
    assertThat(actualFields).as("%s 필드", itemSchemaName).containsExactlyInAnyOrder(fields);
  }
}
