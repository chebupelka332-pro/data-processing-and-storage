package taskj1.protocol;

import taskj1.crypto.KeyMaterial;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class Protocol {
    public static final int MAGIC = 0x4A314B53;
    public static final int VERSION = 1;
    public static final int MAX_NAME_BYTES = 1024;
    public static final int MAX_PRIVATE_KEY_BYTES = 16 * 1024;
    public static final int MAX_CERTIFICATE_BYTES = 64 * 1024;
    public static final int MAX_ERROR_BYTES = 4096;
    private static final int SUCCESS = 0;
    private static final int ERROR = 1;

    private Protocol() {
    }

    public static byte[] request(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_BYTES) {
            throw new IllegalArgumentException("Name must contain 1.." + MAX_NAME_BYTES + " ASCII bytes");
        }
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) == 0 || name.charAt(i) > 127) {
                throw new IllegalArgumentException("Name must be ASCII without embedded NUL bytes");
            }
        }
        return (name + '\0').getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] success(KeyMaterial material) {
        byte[] key = material.privateKeyDer();
        byte[] certificate = material.certificateDer();
        validateEncodedLength(key.length, MAX_PRIVATE_KEY_BYTES);
        validateEncodedLength(certificate.length, MAX_CERTIFICATE_BYTES);
        return encode(SUCCESS, key, certificate);
    }

    public static byte[] error(String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        validateEncodedLength(bytes.length, MAX_ERROR_BYTES);
        return encode(ERROR, bytes, null);
    }

    private static byte[] encode(int status, byte[] first, byte[] second) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeByte(status);
            out.writeInt(first.length);
            if (second != null) {
                out.writeInt(second.length);
            }
            out.write(first);
            if (second != null) {
                out.write(second);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static KeyMaterial readResponse(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != MAGIC) {
            throw new IOException("Invalid response magic");
        }
        if (in.readUnsignedByte() != VERSION) {
            throw new IOException("Unsupported protocol version");
        }
        int status = in.readUnsignedByte();
        if (status == ERROR) {
            int length = readLength(in, MAX_ERROR_BYTES);
            throw new IOException("Server error: " + new String(readBytes(in, length), StandardCharsets.UTF_8));
        }
        if (status != SUCCESS) {
            throw new IOException("Unknown response status: " + status);
        }
        int keyLength = readLength(in, MAX_PRIVATE_KEY_BYTES);
        int certificateLength = readLength(in, MAX_CERTIFICATE_BYTES);
        return new KeyMaterial(readBytes(in, keyLength), readBytes(in, certificateLength));
    }

    private static int readLength(DataInputStream in, int maximum) throws IOException {
        int length = in.readInt();
        if (length <= 0 || length > maximum) {
            throw new IOException("Invalid response field length: " + length);
        }
        return length;
    }

    private static byte[] readBytes(DataInputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static void validateEncodedLength(int length, int maximum) {
        if (length <= 0 || length > maximum) {
            throw new IllegalArgumentException("Invalid encoded field length: " + length);
        }
    }
}
