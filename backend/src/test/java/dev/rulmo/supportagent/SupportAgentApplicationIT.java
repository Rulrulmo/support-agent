package dev.rulmo.supportagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 실제 DB에서 기동하고 Flyway가 돈다 (LLM 호출 없음) */
@SpringBootTest
@Import(TestDatabase.class)
class SupportAgentApplicationIT {

	@Autowired
	JdbcClient jdbc;

	@Test
	void startsWithMigratedDatabase() {
		assertThat(jdbc.sql("SELECT extname FROM pg_extension WHERE extname = 'vector'").query(String.class).optional()).hasValue("vector");
	}
}
