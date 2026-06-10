package zigbee;

import com.zsmartsystems.zigbee.zcl.ZclCommand;
import com.zsmartsystems.zigbee.zcl.ZclFieldDeserializer;
import com.zsmartsystems.zigbee.zcl.ZclFieldSerializer;
import com.zsmartsystems.zigbee.zcl.field.ByteArray;
import com.zsmartsystems.zigbee.zcl.protocol.ZclCommandDirection;
import com.zsmartsystems.zigbee.zcl.protocol.ZclDataType;

/**
 * µBabel <b>DATA</b> command — ZCL cluster-specific (custom) command
 * {@code 0x0003} on the µBabel vendor cluster {@code 0xFF00}.
 *
 * <p>This is the primary µBabel data path since the 2026-06 firmware
 * revision: the ESP sends it via {@code esp_zb_zcl_custom_cluster_cmd_req}
 * (default direction TO_SRV = CLIENT_TO_SERVER, disable-default-response
 * set), and accepts the same command inbound in
 * {@code zb_custom_cmd_handler} ({@code uBabel/components/zigbee/zb_stack.c}).
 * The command id deliberately reuses the legacy DATA <em>attribute</em> id
 * ({@link ZigBeeCoordinator#ATTR_DATA}), mirroring how {@code zb_stack.h}
 * reuses {@code UBABEL_ATTR_DATA_ID} as the custom command id.
 *
 * <p>The single command field is an OCTET_STRING-typed payload — on the wire a
 * 1-byte length prefix followed by the raw bytes ({@code [len:u8][bytes...]});
 * the zsmartsystems OCTET_STRING codec adds/strips the prefix, so
 * {@link #getPayload()} holds only the raw bytes. Those bytes are opaque to
 * this layer: a wrapped {@code ubabel_packet_t} (leading 2-byte big-endian
 * {@code destProto} envelope + packet body) or a fragment frame —
 * fragmentation/reassembly happens above the driver, in the
 * {@code babel-zigbee-protocol} bridge. Max raw length is
 * {@link ZigBeeCoordinator#MAX_PACKET_SIZE_BYTES} (121 B =
 * {@code ZB_MAX_PACKET_SIZE}; ≤122 B with the prefix).
 *
 * <p>Structured after the zsmartsystems generated command classes so the
 * stack's reflective receive pipeline can instantiate it (the public no-arg
 * constructor is required by {@code ZclCluster.getCommandFromId}).
 */
public class UbabelDataCommand extends ZclCommand {

    /** The cluster id this command belongs to —
     *  {@link UbabelZclCluster#CLUSTER_ID} ({@code 0xFF00}). */
    public static final int CLUSTER_ID = UbabelZclCluster.CLUSTER_ID;

    /** The command id ({@code 0x0003}) — numerically identical to the legacy
     *  DATA attribute id ({@link ZigBeeCoordinator#ATTR_DATA}), as on the ESP
     *  side ({@code UBABEL_ATTR_DATA_ID} in {@code zb_stack.h}). */
    public static final int COMMAND_ID = ZigBeeCoordinator.ATTR_DATA;

    /**
     * The OCTET_STRING payload: the raw µBabel bytes (length prefix already
     * stripped by the codec).
     */
    private ByteArray payload;

    /**
     * Default constructor — required by the zsmartsystems reflective receive
     * pipeline; the payload is filled in by
     * {@link #deserialize(ZclFieldDeserializer)}.
     */
    public UbabelDataCommand() {
        clusterId = CLUSTER_ID;
        commandId = COMMAND_ID;
        genericCommand = false;
        commandDirection = ZclCommandDirection.CLIENT_TO_SERVER;
    }

    /**
     * Constructor for the transmit path.
     *
     * @param payload {@link ByteArray} with the raw µBabel bytes to carry
     *                (the OCTET_STRING length prefix is added by the codec)
     */
    public UbabelDataCommand(ByteArray payload) {
        this();
        this.payload = payload;
    }

    /**
     * Gets the raw µBabel payload bytes (a wrapped {@code ubabel_packet_t} or
     * a fragment frame; no length prefix).
     *
     * @return the payload {@link ByteArray}
     */
    public ByteArray getPayload() {
        return payload;
    }

    @Override
    public void serialize(final ZclFieldSerializer serializer) {
        serializer.serialize(payload, ZclDataType.OCTET_STRING);
    }

    @Override
    public void deserialize(final ZclFieldDeserializer deserializer) {
        payload = deserializer.deserialize(ZclDataType.OCTET_STRING);
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder(64);
        builder.append("UbabelDataCommand [");
        builder.append(super.toString());
        builder.append(", payload=");
        builder.append(payload);
        builder.append(']');
        return builder.toString();
    }
}
