package taskj1.crypto;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;

public final class PemFiles {
    private PemFiles() {
    }

    public static void writeNew(Path path, String type, byte[] der) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(der, "der");
        if (!type.matches("[A-Z0-9]+(?: [A-Z0-9]+)*")) {
            throw new IllegalArgumentException("Invalid PEM type");
        }
        String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        FileAttribute<?>[] attributes = new FileAttribute<?>[0];
        if ("PRIVATE KEY".equals(type)
                && Files.getFileStore(path.toAbsolutePath().getParent())
                        .supportsFileAttributeView(PosixFileAttributeView.class)) {
            attributes = new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))};
        }

        try (var channel = Files.newByteChannel(path,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attributes);
             var writer = Channels.newWriter(channel, StandardCharsets.US_ASCII)) {
            writer.write("-----BEGIN " + type + "-----\n");
            writer.write(encoded);
            writer.write("\n-----END " + type + "-----\n");
        }
    }
}
