package zigbee;

import com.zsmartsystems.zigbee.CommandResult;
import com.zsmartsystems.zigbee.ZigBeeEndpoint;
import com.zsmartsystems.zigbee.zcl.ZclAttribute;
import com.zsmartsystems.zigbee.zcl.ZclCluster;
import com.zsmartsystems.zigbee.zcl.ZclCommand;
import com.zsmartsystems.zigbee.zcl.field.ByteArray;
import com.zsmartsystems.zigbee.zcl.protocol.ZclDataType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * The µBabel vendor cluster ({@value #CLUSTER_ID}) as a first-class
 * {@link ZclCluster} subclass, so the zsmartsystems stack can parse the
 * <em>cluster-specific custom commands</em> µBabel rides on.
 *
 * <p>Since the 2026-06 µBabel firmware revision, DATA and DISCOVERY traffic is
 * carried as ZCL cluster-specific commands on this cluster (command ids
 * {@link UbabelDataCommand#COMMAND_ID 0x0003} and
 * {@link UbabelDiscoveryCommand#COMMAND_ID 0x0005} — the same numeric ids as
 * the legacy attributes), not as ZCL Write Attributes. The zsmartsystems
 * receive pipeline resolves an incoming cluster-specific frame against the
 * sender endpoint's cluster instance and instantiates the command class found
 * in that instance's command map
 * ({@code ZigBeeNetworkManager.receiveZclCommand} →
 * {@code ZclCluster.getCommandFromId}/{@code getResponseFromId}). The default
 * placeholder for unknown clusters ({@code ZclCustomCluster}) has <em>empty</em>
 * command maps, so without this class every µBabel command frame would be
 * dropped with a FAILURE default response. {@link ZigBeeCoordinator} therefore
 * replaces the placeholder with an instance of this class on every µBabel
 * endpoint it learns about (replacement is the supported extension point —
 * {@code ZigBeeEndpoint.addInputCluster}/{@code addOutputCluster} deliberately
 * allow swapping out a {@code ZclCustomCluster}).
 *
 * <p>Both the {@code clientCommands} and {@code serverCommands} maps carry the
 * same two command classes: CLIENT_TO_SERVER frames (the direction the ESP
 * firmware sends with) parse through the <em>output</em>-cluster instance's
 * {@code clientCommands}, while SERVER_TO_CLIENT frames would parse through the
 * <em>input</em>-cluster instance's {@code serverCommands} — populating both
 * keeps the receive path robust regardless of the direction bit.
 *
 * <p>The legacy attributes (DATA {@code 0x0003} / HEARTBEAT {@code 0x0004} /
 * DISCOVERY {@code 0x0005}) are still declared in the attribute maps:
 * HEARTBEAT remains a genuine attribute write on the wire (see
 * {@link ZigBeeCoordinator#ATTR_HEARTBEAT}), and legacy DATA/DISCOVERY writes
 * stay parseable for backward compatibility.
 *
 * <p>The cluster id must stay in sync with {@code UBABEL_CUSTOM_CLUSTER_ID} in
 * {@code uBabel/components/zigbee/zb_stack.h} on the ESP / Pico side.
 */
public class UbabelZclCluster extends ZclCluster {

    /** The µBabel vendor cluster id — alias of
     *  {@link ZigBeeCoordinator#UBABEL_CLUSTER_ID} ({@code 0xFF00};
     *  {@code UBABEL_CUSTOM_CLUSTER_ID} in {@code zb_stack.h}). */
    public static final int CLUSTER_ID = ZigBeeCoordinator.UBABEL_CLUSTER_ID;

    /** Human-readable cluster name reported by the zsmartsystems stack. */
    public static final String CLUSTER_NAME = "uBabel";

    /**
     * Creates a µBabel cluster bound to the given endpoint. The command and
     * attribute maps are populated by the {@code initialize*} overrides during
     * base-class construction — no further setup is needed; just add the
     * instance to the endpoint via {@code addInputCluster}/{@code addOutputCluster}.
     *
     * @param endpoint the {@link ZigBeeEndpoint} this cluster instance belongs to
     */
    public UbabelZclCluster(ZigBeeEndpoint endpoint) {
        super(endpoint, CLUSTER_ID, CLUSTER_NAME);
    }

    /**
     * Sends a µBabel DATA command ({@link UbabelDataCommand}) to the remote
     * endpoint this cluster instance is bound to. The payload is serialised as
     * a ZCL OCTET_STRING ({@code [len:u8][bytes...]}) — exactly the encoding
     * the ESP firmware's {@code zb_send_custom_cmd} produces and its
     * {@code zb_custom_cmd_handler} strips on receive.
     *
     * <p><b>Must be called on the instance registered as the endpoint's
     * <em>input</em> cluster.</b> Instances added via {@code addOutputCluster}
     * are flagged as client-side, which makes {@code ZclCluster.sendCommand}
     * rewrite the command direction to SERVER_TO_CLIENT — a direction the ESP
     * firmware does not accept (it expects the default TO_SRV /
     * CLIENT_TO_SERVER). A guard enforces this at call time.
     *
     * <p>The returned future completes when the end device's ZCL Default
     * Response arrives (the command does <em>not</em> disable the default
     * response, so it doubles as the delivery acknowledgement — mirroring the
     * Write Attributes Response of the pre-0.5.0 attribute transport), or when
     * the transaction times out.
     *
     * @param payload the raw bytes to carry (a wrapped {@code ubabel_packet_t}
     *                or a fragment frame)
     * @return a future that completes with the ZCL transaction result
     * @throws IllegalStateException if invoked on a client-side (output
     *         cluster) instance
     */
    public Future<CommandResult> sendData(ByteArray payload) {
        return send(new UbabelDataCommand(payload));
    }

    /**
     * Sends a µBabel DISCOVERY command ({@link UbabelDiscoveryCommand}) to the
     * remote endpoint this cluster instance is bound to. Same encoding,
     * direction constraint, and acknowledgement semantics as
     * {@link #sendData(ByteArray)}.
     *
     * @param payload the raw bytes to carry
     * @return a future that completes with the ZCL transaction result
     * @throws IllegalStateException if invoked on a client-side (output
     *         cluster) instance
     */
    public Future<CommandResult> sendDiscovery(ByteArray payload) {
        return send(new UbabelDiscoveryCommand(payload));
    }

    /**
     * Common dispatch for {@link #sendData}/{@link #sendDiscovery}: guards the
     * direction invariant and forwards to the protected
     * {@link ZclCluster#sendCommand(ZclCommand)} (which addresses the command
     * to this cluster's endpoint and tracks the ZCL transaction).
     */
    private Future<CommandResult> send(ZclCommand command) {
        if (isClient()) {
            throw new IllegalStateException(
                    "µBabel commands must be sent through the input-cluster " +
                    "(server-side) instance — this instance was registered " +
                    "as an output cluster, which would flip the ZCL " +
                    "direction to SERVER_TO_CLIENT and be rejected by the " +
                    "ESP firmware");
        }
        return sendCommand(command);
    }

    /**
     * Client-side command map — commands a client sends <em>to</em> the
     * server. Incoming CLIENT_TO_SERVER cluster-specific frames are resolved
     * against this map (via {@code getCommandFromId} on the sender endpoint's
     * output-cluster instance).
     */
    @Override
    protected Map<Integer, Class<? extends ZclCommand>> initializeClientCommands() {
        return ubabelCommands();
    }

    /**
     * Server-side command map — commands a server sends <em>to</em> the
     * client. Incoming SERVER_TO_CLIENT cluster-specific frames are resolved
     * against this map (via {@code getResponseFromId} on the sender endpoint's
     * input-cluster instance). Identical to the client map: µBabel commands
     * are bidirectional.
     */
    @Override
    protected Map<Integer, Class<? extends ZclCommand>> initializeServerCommands() {
        return ubabelCommands();
    }

    /**
     * Client-side attribute knowledge. The µBabel attributes are declared on
     * both sides — {@code zb_stack.h} marks DATA/DISCOVERY "octet string,
     * bidirectional" — on top of the standard global attributes the base
     * class declares.
     */
    @Override
    protected Map<Integer, ZclAttribute> initializeClientAttributes() {
        return withUbabelAttributes(super.initializeClientAttributes());
    }

    /**
     * Server-side attribute knowledge. See
     * {@link #initializeClientAttributes()} — same set on both sides.
     */
    @Override
    protected Map<Integer, ZclAttribute> initializeServerAttributes() {
        return withUbabelAttributes(super.initializeServerAttributes());
    }

    /** Builds a fresh command map with both µBabel commands. */
    private static Map<Integer, Class<? extends ZclCommand>> ubabelCommands() {
        Map<Integer, Class<? extends ZclCommand>> commandMap =
                new ConcurrentHashMap<>(2);
        commandMap.put(UbabelDataCommand.COMMAND_ID, UbabelDataCommand.class);
        commandMap.put(UbabelDiscoveryCommand.COMMAND_ID,
                       UbabelDiscoveryCommand.class);
        return commandMap;
    }

    /** Adds the three µBabel attributes (DATA / HEARTBEAT / DISCOVERY) to the
     *  given map and returns it. Kept for legacy attribute-write parsing and
     *  for the still-attribute-based heartbeat. */
    private Map<Integer, ZclAttribute> withUbabelAttributes(
            Map<Integer, ZclAttribute> attributeMap) {
        attributeMap.put(ZigBeeCoordinator.ATTR_DATA,
                new ZclAttribute(this, ZigBeeCoordinator.ATTR_DATA, "Data",
                                 ZclDataType.OCTET_STRING, false, true,
                                 true, false));
        attributeMap.put(ZigBeeCoordinator.ATTR_HEARTBEAT,
                new ZclAttribute(this, ZigBeeCoordinator.ATTR_HEARTBEAT,
                                 "Heartbeat",
                                 ZclDataType.UNSIGNED_16_BIT_INTEGER,
                                 false, true, true, false));
        attributeMap.put(ZigBeeCoordinator.ATTR_DISCOVERY,
                new ZclAttribute(this, ZigBeeCoordinator.ATTR_DISCOVERY,
                                 "Discovery", ZclDataType.OCTET_STRING,
                                 false, true, true, false));
        return attributeMap;
    }
}
