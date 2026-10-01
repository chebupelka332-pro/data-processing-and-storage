package taskj1.protocol;

import org.junit.jupiter.api.Test;
import taskj1.crypto.KeyMaterial;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolTest {
    @Test
    void requestIsAsciiNameTerminatedByNul() {
        assertArrayEquals(
                new byte[]{'a', 'l', 'i', 'c', 'e', 0},
                Protocol.request("alice"));
    }

    @Test
    void requestRejectsBadNames() {
        assertThrows(IllegalArgumentException.class, () -> Protocol.request(null));
        assertThrows(IllegalArgumentException.class, () -> Protocol.request(""));
        assertThrows(IllegalArgumentException.class, () -> Protocol.request("a".repeat(1025)));
        assertThrows(IllegalArgumentException.class, () -> Protocol.request("alice\0bob"));
        assertThrows(IllegalArgumentException.class, () -> Protocol.request("алиса"));
        assertThrows(IllegalArgumentException.class, () -> Protocol.request("alice\u0080"));
    }

    @Test
    void successRoundTripPreservesBothBlobs() throws IOException {
        KeyMaterial material = new KeyMaterial(new byte[]{1, 2, 3}, new byte[]{4, 5});
        KeyMaterial decoded = Protocol.readResponse(
                new ByteArrayInputStream(Protocol.success(material)));
        assertArrayEquals(material.privateKeyDer(), decoded.privateKeyDer());
        assertArrayEquals(material.certificateDer(), decoded.certificateDer());
    }

    @Test
    void errorRoundTripSurfacesServerMessage() {
        byte[] response = Protocol.error("Key generation failed; retry the request");
        IOException failure = assertThrows(IOException.class,
                () -> Protocol.readResponse(new ByteArrayInputStream(response)));
        assertTrue(failure.getMessage().contains("retry the request"));
    }

    @Test
    void rejectsCorruptHeaders() throws IOException {
        KeyMaterial material = new KeyMaterial(new byte[]{1}, new byte[]{2});
        byte[] valid = Protocol.success(material);

        byte[] badMagic = valid.clone();
        badMagic[0] = 0x00;
        assertThrows(IOException.class,
                () -> Protocol.readResponse(new ByteArrayInputStream(badMagic)));

        byte[] badVersion = valid.clone();
        badVersion[4] = (byte) (Protocol.VERSION + 1);
        assertThrows(IOException.class,
                () -> Protocol.readResponse(new ByteArrayInputStream(badVersion)));

        byte[] badStatus = valid.clone();
        badStatus[5] = 0x42;
        assertThrows(IOException.class,
                () -> Protocol.readResponse(new ByteArrayInputStream(badStatus)));
    }

    @Test
    void rejectsBadLengthsAndTruncatedBodies() throws IOException {
        assertThrows(IOException.class, () -> Protocol.readResponse(
                new ByteArrayInputStream(headerOnly(0, 0, 0))));
        assertThrows(IOException.class, () -> Protocol.readResponse(
                new ByteArrayInputStream(headerOnly(0, -5, 10))));
        assertThrows(IOException.class, () -> Protocol.readResponse(
                new ByteArrayInputStream(headerOnly(0, Protocol.MAX_PRIVATE_KEY_BYTES + 1, 10))));

        byte[] valid = Protocol.success(new KeyMaterial(new byte[]{1, 2, 3}, new byte[]{4, 5}));
        byte[] truncated = new byte[valid.length - 2];
        System.arraycopy(valid, 0, truncated, 0, truncated.length);
        assertThrows(IOException.class,
                () -> Protocol.readResponse(new ByteArrayInputStream(truncated)));
    }

    @Test
    void successRefusesOversizeBlobs() {
        assertThrows(IllegalArgumentException.class, () -> Protocol.success(
                new KeyMaterial(new byte[Protocol.MAX_PRIVATE_KEY_BYTES + 1], new byte[]{1})));
        assertThrows(IllegalArgumentException.class, () -> Protocol.success(
                new KeyMaterial(new byte[]{1}, new byte[Protocol.MAX_CERTIFICATE_BYTES + 1])));
    }

    @Test
    void errorMessageIsUtf8() throws IOException {
        byte[] response = Protocol.error("ошибка");
        try {
            Protocol.readResponse(new ByteArrayInputStream(response));
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("ошибка"),
                    "message was: " + expected.getMessage());
            return;
        }
        throw new AssertionError("expected an error response");
    }

    @Test
    void maxLengthNameIsAccepted() {
        String name = "a".repeat(Protocol.MAX_NAME_BYTES);
        byte[] encoded = Protocol.request(name);
        assertEquals(Protocol.MAX_NAME_BYTES + 1, encoded.length);
        assertEquals(0, encoded[encoded.length - 1]);
        assertEquals('a', encoded[0]);
    }

    private static byte[] headerOnly(int status, int firstLength, int secondLength) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(Protocol.MAGIC);
        out.writeByte(Protocol.VERSION);
        out.writeByte(status);
        out.writeInt(firstLength);
        out.writeInt(secondLength);
        out.write("truncated".getBytes(StandardCharsets.US_ASCII));
        return bytes.toByteArray();
    }
}
