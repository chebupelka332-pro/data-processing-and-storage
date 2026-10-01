package taskj2.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {

    @Test
    void configParses() {
        Config c = Config.parse(new String[]{"--threads", "3", "--delay-ms", "10", "--mode", "synclist"});
        assertEquals(3, c.threads);
        assertEquals(10, c.delayMs);
        assertEquals(Mode.SYNCLIST, c.mode);
        Config d = Config.parse(new String[]{});
        assertEquals(2, d.threads);
        assertEquals(1000, d.delayMs);
        assertEquals(Mode.CUSTOM, d.mode);
    }
}
