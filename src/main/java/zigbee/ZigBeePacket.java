package zigbee;

import java.nio.charset.StandardCharsets;

/**
 * Carrier for the octet-string value exchanged over the µBabel vendor cluster
 * {@code 0xFF00} — the {@code Data} ({@code 0x0003}) and {@code Discovery}
 * ({@code 0x0005}) attributes.
 *
 * <p>Since the 2026-06 µBabel revision the value is a <strong>wrapped
 * {@code ubabel_packet_t}</strong> — the same form the LoRa side carries — whose
 * leading 2-byte {@code proto_id} (big-endian, {@code htons(1000)}) is the
 * destination-protocol-id envelope, followed by the packet body
 * ({@code recipient}/{@code sender}/{@code message_id}/{@code type}/
 * {@code payload_len}/{@code payload}). The earlier {@code ubabel_zb_packet_t}
 * ({@code id}/{@code val}/{@code payload_len}) framing was scrapped — it carried
 * no useful information — so this class no longer parses or serialises it.
 *
 * <p>The bytes are held <em>verbatim</em>: the driver surfaces the raw attribute
 * value on receive and writes {@link #getPayload()} directly as the OCTET_STRING
 * on transmit. Interpreting the envelope/body (e.g. stripping the 2-byte
 * {@code destProto}) is the consumer's job — the {@code babel-zigbee-protocol}
 * bridge does it, mirroring how the LoRa side surfaces a frame. The ZCL
 * OCTET_STRING length prefix is added/stripped by the ZCL layer and is not part
 * of these bytes.
 */
public class ZigBeePacket {

    private final byte[] payload;

    private ZigBeePacket(Builder b) {
        this.payload = b.payload;
    }

    public byte[] getPayload() { return payload; }

    public String getPayloadAsString() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("ZigBeePacket (%d bytes): ", payload.length));
        for (byte b : payload)
            sb.append(String.format("%02X ", b & 0xFF));
        return sb.toString().trim();
    }

    public static class Builder {
        private byte[] payload = new byte[0];

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
