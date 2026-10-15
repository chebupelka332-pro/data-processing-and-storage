package taskj3.spider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class JsonParserTest {

    @Test
    void parsesBasic() {
        Page p = JsonParser.parsePage("{\"message\": \"hello\", \"successors\": [\"/a\", \"/b\"]}");
        assertEquals("hello", p.getMessage());
        assertEquals(List.of("/a", "/b"), p.getSuccessors());
    }

    @Test
    void fieldOrderDoesNotMatter() {
        Page p = JsonParser.parsePage("{\"successors\": [], \"message\": \"x\"}");
        assertEquals("x", p.getMessage());
        assertTrue(p.getSuccessors().isEmpty());
    }

    @Test
    void handlesEscapes() {
        Page p = JsonParser.parsePage("{\"message\": \"a\\\"b\\\\c\\nd\\u0041\", \"successors\": []}");
        assertEquals("a\"b\\c\ndA", p.getMessage());
    }

    @Test
    void messageWithCommasAndBrackets() {
        Page p = JsonParser.parsePage("{\"message\": \"a, b [c] {d}\", \"successors\": [\"/x,y\"]}");
        assertEquals("a, b [c] {d}", p.getMessage());
        assertEquals(List.of("/x,y"), p.getSuccessors());
    }

    @Test
    void ignoresExtraFields() {
        Page p = JsonParser.parsePage("{\"message\": \"m\", \"extra\": {\"a\": [1,2]}, \"successors\": [], \"n\": 5}");
        assertEquals("m", p.getMessage());
    }

    @Test
    void missingMessageFails() {
        assertThrows(IllegalArgumentException.class,
                () -> JsonParser.parsePage("{\"successors\": []}"));
    }

    @Test
    void missingSuccessorsFails() {
        assertThrows(IllegalArgumentException.class,
                () -> JsonParser.parsePage("{\"message\": \"m\"}"));
    }
}
