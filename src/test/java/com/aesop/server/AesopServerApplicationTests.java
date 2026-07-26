package com.aesop.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "aesop.brain.aesop-root=src/test/resources/fixtures",
    "aesop.brain.conductor-root=src/test/resources/fixtures"
})
class AesopServerApplicationTests {
    @Test
    void contextLoads() {
        // Application context should load without errors
    }
}
