package zigbee;

import com.zsmartsystems.zigbee.zcl.ZclCommand;
import com.zsmartsystems.zigbee.zcl.ZclFieldDeserializer;
import com.zsmartsystems.zigbee.zcl.ZclFieldSerializer;
import com.zsmartsystems.zigbee.zcl.field.ByteArray;
import com.zsmartsystems.zigbee.zcl.protocol.ZclCommandDirection;
import com.zsmartsystems.zigbee.zcl.protocol.ZclDataType;

/**
 * µBabel <b>DISCOVERY</b> command — ZCL cluster-specific (custom) command
 * {@code 0x0005} on the µBabel vendor cluster {@code 0xFF00}.
 *
 * <p>Identical in shape and encoding to {@link UbabelDataCommand} (one
 * OCTET_STRING payload field, CLIENT_TO_SERVER, bidirectional); only the
 * command id differs. The id deliberately reuses the legacy DISCOVERY
 * <em>attribute</em> id ({@link ZigBeeCoordinator#ATTR_DISCOVERY}), mirroring
 * how {@code zb_stack.h} reuses {@code UBABEL_DISCOVERY_ID} as the custom
 * command id. The message kind (DATA / DISCOVERY / …) also lives in the
 * carried {@code ubabel_packet_t.type} field, so {@link ZigBeeCoordinator}
 * surfaces both commands identically to the packet handler.
 *
 * <p>Structured after the zsmartsystems generated command classes so the
 * stack's reflective receive pipeline can instantiate it (the public no-arg
 * constructor is required by {@code ZclCluster.getCommandFromId}).
 */
public class UbabelDiscoveryCommand extends ZclCommand {

    /** The cluster id this command belongs to —
     *  {@link UbabelZclCluster#CLUSTER_ID} ({@code 0xFF00}). */
    public static final int CLUSTER_ID = UbabelZclCluster.CLUSTER_ID;

    /** The command id ({@code 0x0005}) — numerically identical to the legacy
     *  DISCOVERY attribute id ({@link ZigBeeCoordinator#ATTR_DISCOVERY}), as
     *  on the ESP side ({@code UBABEL_DISCOVERY_ID} in {@code zb_stack.h}). */
    public static final int COMMAND_ID = ZigBeeCoordinator.ATTR_DISCOVERY;

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
    public UbabelDiscoveryCommand() {
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
    public UbabelDiscoveryCommand(ByteArray payload) {
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
        builder.append("UbabelDiscoveryCommand [");
        builder.append(super.toString());
        builder.append(", payload=");
        builder.append(payload);
        builder.append(']');
        return builder.toString();
    }
}
