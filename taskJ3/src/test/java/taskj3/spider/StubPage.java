package taskj3.spider;

import java.util.List;

public final class StubPage {

    private final String message;
    private final List<String> successors;
    private final long delayMs;
    private final int status;

    public StubPage(String message, List<String> successors, long delayMs, int status) {
        this.message = message;
        this.successors = List.copyOf(successors);
        this.delayMs = delayMs;
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public List<String> getSuccessors() {
        return successors;
    }

    public long getDelayMs() {
        return delayMs;
    }

    public int getStatus() {
        return status;
    }
}
