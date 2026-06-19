package com.blursome.blursome.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger(OpenAPI) 전역 설정. JWT Bearer 인증 스키마를 등록해 Swagger UI에서 토큰을 넣고 보호된 API를 호출할 수 있게 한다.
 */
@Configuration
public class OpenApiConfig {

  private static final String BEARER_SCHEME = "bearerAuth";

  @Bean
  public OpenAPI blursomeOpenApi() {
    SecurityScheme bearer = new SecurityScheme()
        .type(SecurityScheme.Type.HTTP)
        .scheme("bearer")
        .bearerFormat("JWT");

    return new OpenAPI()
        .info(new Info()
            .title("BlurSome API")
            .description("BlurSome 백엔드 API 문서")
            .version("v1"))
        .components(new Components().addSecuritySchemes(BEARER_SCHEME, bearer))
        .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
  }
}
