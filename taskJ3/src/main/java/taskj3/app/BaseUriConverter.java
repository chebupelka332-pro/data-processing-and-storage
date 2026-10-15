package taskj3.app;

import java.net.URI;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

public final class BaseUriConverter implements ITypeConverter<URI> {

    @Override
    public URI convert(String value) {
        String s = value.trim();
        if (!s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            s = "http://" + s;
        }
        try {
            return URI.create(s);
        } catch (IllegalArgumentException e) {
            throw new TypeConversionException("bad URL '" + value + "': " + e.getMessage());
        }
    }
}
