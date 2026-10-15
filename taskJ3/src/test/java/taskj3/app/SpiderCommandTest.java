package taskj3.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class SpiderCommandTest {

    private static SpiderCommand parse(String... args) {
        SpiderCommand cmd = new SpiderCommand();
        CommandLine cmdLine = new CommandLine(cmd);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmdLine.setOut(new PrintWriter(out));
        cmdLine.setErr(new PrintWriter(err));
        cmdLine.parseArgs(args);
        return cmd;
    }

    @Test
    void parsesBaseUrl() {
        SpiderCommand c = parse("http://localhost:8080");
        assertEquals(URI.create("http://localhost:8080"), c.baseUri);
        assertNull(c.outFile);
    }

    @Test
    void schemeDefaultsToHttp() {
        SpiderCommand c = parse("localhost:3000");
        assertEquals(URI.create("http://localhost:3000"), c.baseUri);
    }

    @Test
    void parsesOptions() {
        SpiderCommand c = parse("http://localhost:8080", "--timeout=5", "--attempts=2", "--out=x.txt");
        assertEquals(5, c.timeoutSec);
        assertEquals(2, c.maxAttempts);
        assertEquals(Path.of("x.txt"), c.outFile);
    }

    @Test
    void shortOutOption() {
        SpiderCommand c = parse("http://localhost:8080", "-o", "out.txt");
        assertEquals(Path.of("out.txt"), c.outFile);
    }

    @Test
    void defaults() {
        SpiderCommand c = parse("http://localhost:8080");
        assertEquals(20, c.timeoutSec);
        assertEquals(3, c.maxAttempts);
        assertEquals(115, c.globalTimeoutSec);
    }

    @Test
    void missingBaseIsUsageError() {
        int exit = new CommandLine(new SpiderCommand()).execute();
        assertEquals(2, exit);
    }

    @Test
    void unknownOptionIsUsageError() {
        CommandLine cmdLine = new CommandLine(new SpiderCommand());
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmdLine.setOut(new PrintWriter(out));
        cmdLine.setErr(new PrintWriter(err));
        int exit = cmdLine.execute("http://localhost:8080", "--nope");
        assertEquals(2, exit);
    }
}
