package zigbee;

import java.nio.charset.StandardCharsets;

/**
 * Wire-format value object for {@code ubabel_zb_packet_t} — the octet-string
 * payload carried in the {@code Data} attribute (id {@code 0x0003}) of the
 * µBabel vendor cluster {@code 0xFF00}.
 *
 * <p>Layout (little-endian, no length prefix — the ZCL OCTET_STRING wrapper
 * strips the leading length byte before the value reaches application code):
 * <pre>
 * typedef struct __attribute__((packed)) {
 *     uint16_t id;          // little-endian
 *     uint16_t val;         // little-endian
 *     uint8_t  payload_len;
 *     uint8_t  payload[];   // UTF-8 string, payload_len bytes
 * } ubabel_zb_packet_t;
 * </pre>
 *
 * <p>The constants in this class must stay in sync with {@code zigbee.h} on
 * the ESP / Pico side.
 */
public class ZigBeePacket {

    private final int id;
    private final int val;
    private final byte[] payload;

    private ZigBeePacket(Builder b) {
        this.id = b.id;
        this.val = b.val;
        this.payload = b.payload;
    }

    public int getId() { return id; }

    public int getVal() { return val; }

    public byte[] getPayload() { return payload; }

    public String getPayloadAsString() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    /**
     * Serialises this packet to the on-the-wire {@code ubabel_zb_packet_t}
     * byte layout. The returned array carries no ZCL length prefix; the
     * caller is responsible for wrapping it in an OCTET_STRING when sending
     * via a {@code WriteAttributesCommand}.
     */
    public byte[] toBytes() {
        final int headerLen = 5;
        byte[] frame = new byte[headerLen + payload.length];

        frame[0] = (byte)(id & 0xFF);
        frame[1] = (byte)((id >> 8) & 0xFF);
        frame[2] = (byte)(val & 0xFF);
        frame[3] = (byte)((val >> 8) & 0xFF);
        frame[4] = (byte)(payload.length & 0xFF);

        System.arraycopy(payload, 0, frame, headerLen, payload.length);
        return frame;
    }

    /**
     * Parses a raw {@code ubabel_zb_packet_t} byte array (as delivered by
     * {@code ByteArray.get()} on the OCTET_STRING attribute value). Returns
     * {@code null} on truncated or malformed frames.
     */
    public static ZigBeePacket fromBytes(byte[] raw) {
        final int headerLen = 5;
        if (raw == null || raw.length < headerLen) {
            return null;
        }

        int id = (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8);
        int val = (raw[2] & 0xFF) | ((raw[3] & 0xFF) << 8);
        int payloadLen = raw[4] & 0xFF;

        if (headerLen + payloadLen > raw.length) {
            return null;
        }

        byte[] payload = new byte[payloadLen];
        System.arraycopy(raw, headerLen, payload, 0, payloadLen);

        return new Builder()
                .id(id)
                .val(val)
                .payload(payload)
                .build();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ZigBeePacket:\n");
        sb.append(String.format("Id      : %d (0x%04X)\n", id, id));
        sb.append(String.format("Val     : %d (0x%04X)\n", val, val));
        sb.append(String.format("Payload : '%s' (%d bytes)\n",
                                getPayloadAsString(), payload.length));
        sb.append("Raw     : ");
        for (byte b : toBytes())
            sb.append(String.format("%02X ", b & 0xFF));
        return sb.toString().trim();
    }

    public static class Builder {
        private int id;
        private int val;
        private byte[] payload = new byte[0];

        public Builder id(int id) {
            this.id = id & 0xFFFF;
            return this;
        }

        public Builder val(int val) {
            this.val = val & 0xFFFF;
            return this;
        }

        public Builder payload(byte[] data) {
            this.payload = data == null ? new byte[0] : data.clone();
            return this;
        }

        public Builder payload(String text) {
            this.payload = text == null
                    ? new byte[0]
                    : text.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        public ZigBeePacket build() { return new ZigBeePacket(this); }
    }
}
