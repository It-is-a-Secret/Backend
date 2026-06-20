package com.blursome.blursome;

import com.blursome.blursome.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

// 외부 인프라 없이 전체 컨텍스트 로딩만 검증한다. DB는 Testcontainers(MySQL)가, 그 외
// Redis/Mail/시크릿은 test 프로파일의 더미 설정이 제공하므로 CI에서 secrets 없이도 동작한다.
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BlursomeApplicationTests {

	@Test
	void contextLoads() {
	}

}
