package packet;

import com.zsmartsystems.zigbee.zcl.field.ByteArray;
import java.nio.charset.StandardCharsets;

public class UBabelPacket {
    public final int id;
    public final int val;
    public final byte[] payload;

    public UBabelPacket(int id, int val, byte[] payload) {
        this.id = id;
        this.val = val;
        this.payload = payload != null ? payload : new byte[0];
    }

    public UBabelPacket(int id, int val, String payload) {
        this(id, val, payload.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        String payload_str = new String(payload, StandardCharsets.UTF_8);
        return "uBabelPacket{id=" + id + ", val=" + val +
            ", payload=" + payload_str + "}";
    }

    /** Deserialize from a received ByteArray (offset 0, no length prefix). */
    public static UBabelPacket fromByteArray(ByteArray raw) {
        byte[] b = raw.get();
        int id = (b[0] & 0xFF) | ((b[1] & 0xFF) << 8);
        int val = (b[2] & 0xFF) | ((b[3] & 0xFF) << 8);
        int payloadLen = b[4] & 0xFF;
        byte[] payload = new byte[payloadLen];
        System.arraycopy(b, 5, payload, 0, payloadLen);
        return new UBabelPacket(id, val, payload);
    }

    /** Serialize for transmission. Stack adds OCTET_STRING length prefix. */
    public ByteArray toByteArray() {
        byte[] b = new byte[5 + payload.length];
        b[0] = (byte)(id & 0xFF);
        b[1] = (byte)((id >> 8) & 0xFF);
        b[2] = (byte)(val & 0xFF);
        b[3] = (byte)((val >> 8) & 0xFF);
        b[4] = (byte)(payload.length & 0xFF);
        System.arraycopy(payload, 0, b, 5, payload.length);
        return new ByteArray(b);
    }
}
