package com.example.commandlinerunner;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class CommandlinerunnerApplicationTests {

	@Autowired
	CLR clr;

	@Test
	public void contextLoads() {
		assertNotNull(this.clr, "@Autowired CLR field");
	}

}
