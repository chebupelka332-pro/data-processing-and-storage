package taskj3.spider;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class Page {

    private final String message;
    private final List<String> successors;

    @JsonCreator
    public Page(
            @JsonProperty("message") String message,
            @JsonProperty("successors") List<String> successors) {
        this.message = Objects.requireNonNull(message, "message");
        this.successors = List.copyOf(Objects.requireNonNull(successors, "successors"));
    }

    public String getMessage() {
        return message;
    }

    public List<String> getSuccessors() {
        return successors;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Page)) {
            return false;
        }
        Page that = (Page) other;
        return message.equals(that.message) && successors.equals(that.successors);
    }

    @Override
    public int hashCode() {
        return Objects.hash(message, successors);
    }

    @Override
    public String toString() {
        return "Page{message='" + message + "', successors=" + successors + "}";
    }
}
