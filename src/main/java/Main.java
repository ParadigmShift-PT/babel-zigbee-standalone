import com.zsmartsystems.zigbee.*;
import com.zsmartsystems.zigbee.app.ZigBeeNetworkExtension;
import com.zsmartsystems.zigbee.app.basic.ZigBeeBasicServerExtension;
import com.zsmartsystems.zigbee.app.discovery.ZigBeeNodeServiceDiscoverer;
import com.zsmartsystems.zigbee.database.ZigBeeNetworkDataStore;
import com.zsmartsystems.zigbee.database.ZigBeeNodeDao;
import com.zsmartsystems.zigbee.dongle.ember.EmberNcp;
import com.zsmartsystems.zigbee.dongle.ember.ZigBeeDongleEzsp;
import com.zsmartsystems.zigbee.dongle.ember.ezsp.structure.EmberNetworkStatus;
import com.zsmartsystems.zigbee.dongle.ember.ezsp.structure.EzspConfigId;
import com.zsmartsystems.zigbee.dongle.ember.ezsp.structure.EzspDecisionId;
import com.zsmartsystems.zigbee.dongle.ember.ezsp.structure.EzspPolicyId;
import com.zsmartsystems.zigbee.security.ZigBeeKey;
import com.zsmartsystems.zigbee.serial.ZigBeeSerialPort;
import com.zsmartsystems.zigbee.serialization.DefaultDeserializer;
import com.zsmartsystems.zigbee.serialization.DefaultSerializer;
import com.zsmartsystems.zigbee.transport.TransportConfig;
import com.zsmartsystems.zigbee.transport.TransportConfigOption;
import com.zsmartsystems.zigbee.transport.TrustCentreJoinMode;
import com.zsmartsystems.zigbee.transport.ZigBeePort.FlowControl;
import com.zsmartsystems.zigbee.zcl.ZclAttribute;
import com.zsmartsystems.zigbee.zcl.ZclCluster;
import com.zsmartsystems.zigbee.zcl.clusters.general.WriteAttributesCommand;
import com.zsmartsystems.zigbee.zcl.field.ByteArray;
import com.zsmartsystems.zigbee.zcl.field.WriteAttributeRecord;
import com.zsmartsystems.zigbee.zcl.protocol.ZclDataType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import packet.UBabelPacket;

public class Main {

    // -------------------------------------------------------------------------
    // Network configuration
    // -------------------------------------------------------------------------

    private static final String SERIAL_PORT = "/dev/tty.usbserial-2130";
    private static final int SERIAL_BAUD = 115200;
    private static final ZigBeeChannel ZIGBEE_CHANNEL =
        ZigBeeChannel.CHANNEL_13;
    private static final int ZIGBEE_PAN_ID = 0xE5F2;
    // private static final int ZIGBEE_PAN_ID =
    // 0xFFFF; // don't care, the network sets a random one
    private static final ExtendedPanId ZIGBEE_EPAN_ID =
        new ExtendedPanId("001122334455667788");
    // private static final ExtendedPanId ZIGBEE_EPAN_ID =
    // ExtendedPanId.createRandom();

    // ZigBee well-known join key (ZigBeeAlliance09). Only needed if the NCP
    // supports transient link keys — on this hardware it returns
    // LIBRARY_NOT_PRESENT.
    private static final ZigBeeKey KEY_ALLIANCE09 = new ZigBeeKey(
        new int[] {0x5A, 0x69, 0x67, 0x42, 0x65, 0x65, 0x41, 0x6C, 0x6C, 0x69,
                   0x61, 0x6E, 0x63, 0x65, 0x30, 0x39});

    private static final ZigBeeKey KEY_NETWORK = new ZigBeeKey(
        new int[] {0x01, 0x03, 0x05, 0x07, 0x09, 0x0B, 0x0D, 0x0F, 0x00, 0x02,
                   0x04, 0x06, 0x08, 0x0A, 0x0C, 0x0D});

    private static final ZigBeeKey KEY_TC_LINK = new ZigBeeKey(
        new int[] {0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD,
                   0xEF, 0x01, 0x23, 0x45, 0x67, 0x89});

    // -------------------------------------------------------------------------
    // MicroBabel protocol constants — must match zigbee.h on the ESP side
    // -------------------------------------------------------------------------

    // ZigBee Home Automation profile, used on both coordinator and end device
    // endpoints
    private static final int HA_PROFILE_ID = 0x0104;

    // Coordinator listens on endpoint 1; end devices transmit from endpoint 10
    private static final int UBABEL_C_ENDPOINT = 1;
    private static final int UBABEL_ED_ENDPOINT = 10;

    // Vendor-specific cluster carrying all MicroBabel traffic
    private static final int UBABEL_CLUSTER_ID = 0xFF00;

    // Attribute IDs within the custom cluster
    private static final int UBABEL_ATTR_DATA_ID =
        0x0003; // OCTET_STRING: ubabel_zb_packet_t
    private static final int UBABEL_ATTR_HEARTBEAT_ID =
        0x0004; // UINT16: monotonic counter

    // HA device ID used by the ESP end device (custom attribute device)
    private static final int ESP_HA_CUSTOM_DEVICE_ID = 0xFFF2;

    // -------------------------------------------------------------------------
    // Runtime state
    // -------------------------------------------------------------------------

    // Per-device state keyed by IEEE address (persistent across network address
    // changes)
    private static final Map<IeeeAddress, DeviceState> deviceStates =
        new ConcurrentHashMap<>();

    // IEEE address of the coordinator dongle, set once after initialize()
    private static IeeeAddress coordinatorIeee;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        ZigBeeSerialPort port = new ZigBeeSerialPort(
            SERIAL_PORT, SERIAL_BAUD, FlowControl.FLOWCONTROL_OUT_NONE);

        ZigBeeDongleEzsp dongle = new ZigBeeDongleEzsp(port);
        // Allow unsecured joins at the EZSP level before the stack applies its
        // own policy
        dongle.updateDefaultPolicy(EzspPolicyId.EZSP_TRUST_CENTER_POLICY,
                                   EzspDecisionId.EZSP_ALLOW_JOINS);

        ZigBeeNetworkManager manager = new ZigBeeNetworkManager(dongle);
        manager.setSerializer(DefaultSerializer.class,
                              DefaultDeserializer.class);

        // Stateless data store — nodes are not persisted across restarts.
        // TODO: replace with a file-backed store when persistence is needed.
        manager.setNetworkDataStore(new ZigBeeNetworkDataStore() {
            @Override
            public Set<IeeeAddress> readNetworkNodes() {
                return new HashSet<>();
            }

            @Override
            public ZigBeeNodeDao readNode(IeeeAddress ieee) {
                return null;
            }

            @Override
            public void writeNode(ZigBeeNodeDao node) {}

            @Override
            public void removeNode(IeeeAddress ieee) {}
        });

        ZigBeeStatus status = manager.initialize();
        if (status != ZigBeeStatus.SUCCESS) {
            System.err.println("Failed to initialize ZigBee stack: " + status);
            return;
        }

        // Capture the dongle's IEEE address so we can identify the coordinator
        // node object later (the stack does not reliably populate NWK=0000 on
        // it)
        coordinatorIeee = dongle.getIeeeAddress();
        System.out.println("Coordinator IEEE: " + coordinatorIeee);

        configureTransport(dongle, manager);
        configureNetwork(manager);
        registerListeners(manager);

        status = manager.startup(true);
        if (status != ZigBeeStatus.SUCCESS) {
            System.err.println("Failed to start ZigBee network: " + status);
            return;
        }

        // The coordinator node is added asynchronously during startup. Poll
        // briefly to confirm it appeared before opening the network for joins.
        if (!waitForCoordinator(manager)) {
            System.err.println("Coordinator node never registered — aborting");
            return;
        }

        ZigBeeNode coordNode = manager.getNode(coordinatorIeee);
        if (coordNode != null) {
            registerCoordinatorEndpoint(coordNode);
        }

        // Open the network indefinitely so end devices can join or rejoin.
        // Reduce or remove permitJoin duration in production.
        manager.permitJoin(254);
        System.out.println(
            "Network open for joining. Listening for packets...");

        Thread senderThread = new Thread(() -> {
            int i = 0;
            while (!Thread.currentThread().isInterrupted()) {
                System.out.println("thread loop: " + i);

                try {
                    for (IeeeAddress ieee :
                         new ArrayList<>(deviceStates.keySet())) {
                        UBabelPacket pkt = new UBabelPacket(
                            i, i * 2,
                            "123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ\n"
                                + "123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ\n"
                                + "123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ\n"
                                + "123456789ABCDE"

                        );
                        sendToDevice(manager, ieee, pkt);
                        i++;
                    }
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        senderThread.setDaemon(true);
        senderThread.start();

        Thread.currentThread().join();
    }

    // -------------------------------------------------------------------------
    // Setup helpers
    // -------------------------------------------------------------------------

    /**
     * Configures transport-layer options on the dongle: join mode and the set
     * of clusters the coordinator endpoint will advertise.
     */
    private static void configureTransport(ZigBeeDongleEzsp dongle,
                                           ZigBeeNetworkManager manager)
        throws Exception {
        TransportConfig config = new TransportConfig();
        config.addOption(TransportConfigOption.TRUST_CENTRE_JOIN_MODE,
                         TrustCentreJoinMode.TC_JOIN_INSECURE);

        // Declare the custom cluster so the NCP endpoint descriptor includes it
        Collection<Integer> clusters = new ArrayList<>();
        clusters.add(UBABEL_CLUSTER_ID);
        config.addOption(TransportConfigOption.SUPPORTED_INPUT_CLUSTERS,
                         clusters);
        config.addOption(TransportConfigOption.SUPPORTED_OUTPUT_CLUSTERS,
                         clusters);
        dongle.updateTransportConfig(config);

        EmberNcp ncp = dongle.getEmberNcp();

        // Force the NCP to leave any previously formed network so we can
        // reinitialise cleanly with our chosen PAN ID and channel.
        ncp.leaveNetwork();
        while (ncp.getNetworkState() != EmberNetworkStatus.EMBER_NO_NETWORK) {
            Thread.sleep(500);
        }

        // Attempt to install the ZigBee Alliance well-known join key.
        // This returns LIBRARY_NOT_PRESENT on some NCP firmware builds — safe
        // to ignore.
        ncp.addTransientLinkKey(new IeeeAddress("FFFFFFFFFFFFFFFF"),
                                KEY_ALLIANCE09);
    }

    /**
     * Sets network parameters (channel, PAN ID, keys) and registers the custom
     * cluster with the manager so the stack accepts inbound frames for it.
     */
    private static void configureNetwork(ZigBeeNetworkManager manager) {
        manager.setZigBeeChannel(ZIGBEE_CHANNEL);
        manager.setZigBeePanId(ZIGBEE_PAN_ID);
        manager.setZigBeeExtendedPanId(ZIGBEE_EPAN_ID);
        manager.setZigBeeNetworkKey(KEY_NETWORK);
        manager.setZigBeeLinkKey(KEY_TC_LINK);

        // ZigBeeBasicServerExtension responds to ZDO Basic cluster requests,
        // which some coordinators require during device discovery.
        ZigBeeNetworkExtension basicExt = new ZigBeeBasicServerExtension();
        manager.addExtension(basicExt);

        // Register the custom cluster so ClusterMatcher includes it in the
        // coordinator's endpoint descriptor sent during ZDO exchanges.
        manager.addSupportedClientCluster(UBABEL_CLUSTER_ID);
        manager.addSupportedServerCluster(UBABEL_CLUSTER_ID);
    }

    /**
     * Registers all event listeners on the manager: network state, device
     * announcements, node lifecycle, and the global command listener that
     * handles incoming MicroBabel packets.
     */
    private static void registerListeners(ZigBeeNetworkManager manager) {
        manager.addNetworkStateListener(
            state -> System.out.println("Network state: " + state));

        // When a device announces itself, manually create a node entry and
        // trigger rediscovery. This is necessary because the zsmartsystems
        // stack will not route incoming frames from unknown nodes.
        // TODO: replace with proper ZDO-driven discovery once the ZDO exchange
        // issue is debugged (rediscoverNode currently produces no ZDO traffic).
        manager.addAnnounceListener(new ZigBeeAnnounceListener() {
            @Override
            public void deviceStatusUpdate(ZigBeeNodeStatus status,
                                           Integer networkAddress,
                                           IeeeAddress ieeeAddress) {
                System.out.printf("Announce: %s addr=0x%04X ieee=%s%n", status,
                                  networkAddress, ieeeAddress);
                if (status == ZigBeeNodeStatus.UNSECURED_JOIN) {
                    ZigBeeNode node = new ZigBeeNode(manager, ieeeAddress);

                    node.setNetworkAddress(networkAddress);
                    manager.updateNode(node);
                    ZigBeeNodeServiceDiscoverer discoverer =
                        new ZigBeeNodeServiceDiscoverer(manager, node);
                    discoverer.startDiscovery();
                }
            }
        });

        manager.addNetworkNodeListener(new ZigBeeNetworkNodeListener() {
            @Override
            public void nodeAdded(ZigBeeNode node) {
                System.out.println("\n\nNode added: " + node.getIeeeAddress());
                if (node.getIeeeAddress().equals(coordinatorIeee)) {
                    // registerCoordinatorEndpoint(node);
                } else {
                    registerEndDeviceEndpoint(node);
                }
            }

            @Override
            public void nodeUpdated(ZigBeeNode node) {
                System.out.println(
                    "\n\nNode updated: " + node.getIeeeAddress() +
                    " endpoints=" + node.getEndpoints() +
                    " ep10=" + node.getEndpoint(UBABEL_ED_ENDPOINT) + "\n\n");
            }

            @Override
            public void nodeRemoved(ZigBeeNode node) {
                deviceStates.remove(node.getIeeeAddress());
                System.out.println("Node removed: " + node.getIeeeAddress());
            }
        });

        // Global command listener — receives all parsed ZCL commands before
        // cluster-level routing. Used here because the coordinator node's NWK
        // address is not populated by the stack, which breaks cluster-level
        // dispatch. Intercept WriteAttributesCommand directly instead.
        manager.addCommandListener(command -> {
            if (!(command instanceof WriteAttributesCommand))
                return;
            WriteAttributesCommand writeCmd = (WriteAttributesCommand)command;
            if (writeCmd.getClusterId() != UBABEL_CLUSTER_ID)
                return;
            handleWriteAttributes(manager, writeCmd);
        });
    }

    private static void sendToDevice(ZigBeeNetworkManager manager,
                                     IeeeAddress ieee, UBabelPacket pkt) {
        ZigBeeNode coordNode = manager.getNode(coordinatorIeee);
        ZigBeeNode destNode = manager.getNode(ieee);
        System.out.printf(
            "\n\n\n AFTER GETTING NODES Sending to %s: %s%n\n\n\n", ieee, pkt);
        if (coordNode == null || destNode == null)
            return;

        ZigBeeEndpoint srcEp = coordNode.getEndpoint(UBABEL_C_ENDPOINT);
        ZigBeeEndpoint dstEp = destNode.getEndpoint(UBABEL_ED_ENDPOINT);
        System.out.printf("\n\n\n AFTER GETTING ENDPOINTS\n");
        System.out.printf("srcEp=%s dstEp=%s%n\n\n\n", srcEp, dstEp);
        if (srcEp == null || dstEp == null)
            return;

        // ZclCluster outCluster = srcEp.getOutputCluster(UBABEL_CLUSTER_ID);
        // System.out.printf("\n\n\n AFTER GETTING OUT CLUSTER\n\n\n");
        // if (outCluster == null)
        // return;
        ZclCluster destCluster = dstEp.getInputCluster(UBABEL_CLUSTER_ID);
        System.out.printf("\n\n\n AFTER GETTING DEST CLUSTER\n\n\n");
        if (destCluster == null)
            return;
        System.out.printf("Sending to %s: %s%n", ieee, pkt);
        // Future<CommandResult> result = outCluster.writeAttribute(
        Future<CommandResult> result = destCluster.writeAttribute(
            UBABEL_ATTR_DATA_ID, ZclDataType.OCTET_STRING, pkt.toByteArray());
        System.out.printf("Write submitted, future: %s%n", result);
    }

    // -------------------------------------------------------------------------
    // Endpoint registration
    // -------------------------------------------------------------------------

    private static void registerCoordinatorEndpoint(ZigBeeNode node) {
        if (node.getEndpoint(UBABEL_C_ENDPOINT) != null)
            return;

        ZigBeeEndpoint ep = new ZigBeeEndpoint(node, UBABEL_C_ENDPOINT);
        ep.setProfileId(HA_PROFILE_ID);
        ep.setDeviceId(0x0000);

        ZclCluster inCluster =
            new ZclCluster(ep, UBABEL_CLUSTER_ID, "uBabel") {};
        inCluster.addLocalAttributes(ubabelAttributes(inCluster));
        ep.addInputCluster(inCluster);

        ZclCluster outCluster =
            new ZclCluster(ep, UBABEL_CLUSTER_ID, "uBabel") {};
        outCluster.addAttributes(ubabelAttributes(outCluster));
        ep.addOutputCluster(outCluster);

        node.addEndpoint(ep);

        System.out.println("\n\n\nCoordinator endpoint registered: " +
                           node.getIeeeAddress() + "\n\n\n");
    }

    private static void registerEndDeviceEndpoint(ZigBeeNode node) {
        ZigBeeEndpoint ep = node.getEndpoint(UBABEL_ED_ENDPOINT);
        if (ep == null)
            return;
        ZclCluster cluster = ep.getOutputCluster(UBABEL_CLUSTER_ID);
        if (cluster == null)
            return;
        if (deviceStates.containsKey(node.getIeeeAddress()))
            return;
        cluster.addLocalAttributes(ubabelAttributes(cluster));
        deviceStates.put(node.getIeeeAddress(), new DeviceState());

        System.out.println("End device endpoint registered: " +
                           node.getIeeeAddress());
    }

    /**
     * Returns the set of custom attributes for cluster 0xFF00.
     * Both attributes are declared writable so the stack accepts Write
     * Attribute commands from end devices without rejecting them as
     * unsupported.
     */
    private static Set<ZclAttribute> ubabelAttributes(ZclCluster cluster) {
        return Set.of(new ZclAttribute(cluster, UBABEL_ATTR_DATA_ID, "Data",
                                       ZclDataType.OCTET_STRING, false, true,
                                       true, false),
                      new ZclAttribute(cluster, UBABEL_ATTR_HEARTBEAT_ID,
                                       "Heartbeat",
                                       ZclDataType.UNSIGNED_16_BIT_INTEGER,
                                       false, true, true, false));
    }

    // -------------------------------------------------------------------------
    // Packet handling
    // -------------------------------------------------------------------------

    /**
     * Dispatches a received Write Attribute command to the appropriate handler
     * based on attribute ID. Resolves the source IEEE address from the node
     * table if available, falling back to a formatted hex string.
     */
    private static void handleWriteAttributes(ZigBeeNetworkManager manager,
                                              WriteAttributesCommand cmd) {
        ZigBeeNode sourceNode =
            manager.getNode(cmd.getSourceAddress().getAddress());
        IeeeAddress ieee =
            sourceNode != null
                ? sourceNode.getIeeeAddress()
                : new IeeeAddress(String.format(
                      "%016X", cmd.getSourceAddress().getAddress()));

        for (WriteAttributeRecord record : cmd.getRecords()) {
            switch (record.getAttributeIdentifier()) {
            case UBABEL_ATTR_DATA_ID -> {
                parseUbabelPacket((ByteArray)record.getAttributeValue(), ieee);
            }
            case UBABEL_ATTR_HEARTBEAT_ID -> {
                int counter = ((Number)record.getAttributeValue()).intValue();
                DeviceState state =
                    deviceStates.computeIfAbsent(ieee, k -> new DeviceState());
                state.heartbeatCounter = counter;
                System.out.printf("Heartbeat from %s: counter=%d%n", ieee,
                                  counter);
            }
            }
        }
    }

    private static void parseUbabelPacket(ByteArray bytes, IeeeAddress ieee) {
        if (bytes == null || bytes.size() < 5)
            return;

        UBabelPacket pkt = UBabelPacket.fromByteArray(bytes);

        DeviceState state =
            deviceStates.computeIfAbsent(ieee, k -> new DeviceState());
        state.lastId = pkt.id;
        state.lastVal = pkt.val;
        state.lastPayload = pkt.payload;

        System.out.printf("Packet from %s: %s\n", ieee, pkt);
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /**
     * Polls the manager until the coordinator node appears in the node table.
     * The node is added asynchronously during startup, so a brief wait is
     * needed before any post-startup operations that reference it.
     */
    private static boolean waitForCoordinator(ZigBeeNetworkManager manager)
        throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            if (manager.getNode(coordinatorIeee) != null)
                return true;
            Thread.sleep(250);
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Per-device state
    // -------------------------------------------------------------------------

    private static class DeviceState {
        int lastId;
        int lastVal;
        byte[] lastPayload;
        int heartbeatCounter;

        @Override
        public String toString() {
            String payload_str =
                new String(lastPayload, StandardCharsets.UTF_8);
            return String.format(
                "DeviceState[id=%d, val=%d, heartbeat=%d, payload='%s']",
                lastId, lastVal, heartbeatCounter, payload_str);
        }
    }
}
