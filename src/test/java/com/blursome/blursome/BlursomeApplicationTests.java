package com.blursome.blursome;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// 외부 인프라(MySQL·Redis·SMTP) 없이 전체 컨텍스트 로딩만 검증한다. test 프로파일이
// H2 인메모리 DB와 더미 설정을 제공하므로 CI에서 secrets 없이도 동작한다.
@SpringBootTest
@ActiveProfiles("test")
class BlursomeApplicationTests {

	@Test
	void contextLoads() {
	}

}
