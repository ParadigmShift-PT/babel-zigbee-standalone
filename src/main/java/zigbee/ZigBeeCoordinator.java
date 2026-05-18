package zigbee;

import com.zsmartsystems.zigbee.ExtendedPanId;
import com.zsmartsystems.zigbee.IeeeAddress;
import com.zsmartsystems.zigbee.ZigBeeAnnounceListener;
import com.zsmartsystems.zigbee.ZigBeeChannel;
import com.zsmartsystems.zigbee.ZigBeeEndpoint;
import com.zsmartsystems.zigbee.ZigBeeNetworkManager;
import com.zsmartsystems.zigbee.app.ZigBeeNetworkExtension;
import com.zsmartsystems.zigbee.ZigBeeNetworkNodeListener;
import com.zsmartsystems.zigbee.ZigBeeNode;
import com.zsmartsystems.zigbee.ZigBeeNodeStatus;
import com.zsmartsystems.zigbee.ZigBeeStatus;
import com.zsmartsystems.zigbee.app.basic.ZigBeeBasicServerExtension;
import com.zsmartsystems.zigbee.database.ZigBeeNetworkDataStore;
import com.zsmartsystems.zigbee.database.ZigBeeNodeDao;
import com.zsmartsystems.zigbee.dongle.ember.EmberNcp;
import com.zsmartsystems.zigbee.dongle.ember.ZigBeeDongleEzsp;
import com.zsmartsystems.zigbee.dongle.ember.ezsp.structure.EmberNetworkStatus;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import com.zsmartsystems.zigbee.CommandResult;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

/**
 * ZigBee coordinator on top of the zsmartsystems stack with an Ember (EZSP)
 * USB dongle backend. Forms a ZigBee Home Automation network on a fixed
 * channel/PAN, registers the µBabel vendor cluster, and surfaces incoming
 * µBabel packets and heartbeats to caller-supplied handlers.
 *
 * <p>Wire constants ({@link #HA_PROFILE_ID}, the two endpoints, the cluster
 * id, the two attribute ids and the end-device ID configured via
 * {@link ZigBeeConfig.Builder#endDeviceId(int)} — default
 * {@link #DEFAULT_END_DEVICE_ID}) must stay in sync with {@code zigbee.h} on
 * the ESP / Pico side.
 */
public class ZigBeeCoordinator {

    public static final int HA_PROFILE_ID = 0x0104;
    public static final int COORDINATOR_ENDPOINT = 1;
    public static final int END_DEVICE_ENDPOINT = 10;
    public static final int UBABEL_CLUSTER_ID = 0xFF00;
    public static final int ATTR_DATA = 0x0003;
    public static final int ATTR_HEARTBEAT = 0x0004;
    /** Default HA device id advertised by the ESP firmware. Override via
     *  {@link ZigBeeConfig.Builder#endDeviceId(int)} if your firmware uses a
     *  different value. */
    public static final int DEFAULT_END_DEVICE_ID = 0xFFF2;

    /** Maximum on-the-wire size of the {@code ubabel_zb_packet_t} carried in
     *  the {@code Data} attribute. The ZBDongle-E ZCL transport caps the
     *  attribute value at 128 bytes; subtracting 6 bytes of ZCL framing
     *  overhead and 1 byte for the OCTET_STRING length prefix leaves 121
     *  bytes for the actual packet (see {@code ubabel_zb_proto.h} in the
     *  uBabel firmware). */
    public static final int MAX_PACKET_SIZE_BYTES = 121;

    /** Maximum µBabel payload size in bytes — {@link #MAX_PACKET_SIZE_BYTES}
     *  minus the 5-byte {@code ubabel_zb_packet_t} header. */
    public static final int MAX_PAYLOAD_SIZE_BYTES = MAX_PACKET_SIZE_BYTES - 5;

    private final ZigBeeConfig cfg;

    private ZigBeeSerialPort port;
    private ZigBeeDongleEzsp dongle;
    private ZigBeeNetworkManager manager;
    private IeeeAddress coordinatorIeee;

    private final Map<IeeeAddress, DeviceState> deviceStates =
            new ConcurrentHashMap<>();

    private volatile BiConsumer<IeeeAddress, ZigBeePacket> packetHandler;
    private volatile BiConsumer<IeeeAddress, Integer> heartbeatHandler;

    public ZigBeeCoordinator(ZigBeeConfig cfg) {
        if (cfg == null) {
            throw new IllegalArgumentException("ZigBeeConfig must not be null");
        }
        if (cfg.serialPort == null || cfg.serialPort.isEmpty()) {
            throw new IllegalArgumentException(
                    "ZigBeeConfig.serialPort must be set");
        }
        this.cfg = cfg;
    }

    /**
     * Brings up the coordinator: opens the serial port, initialises the EZSP
     * dongle, configures the trust-centre policy, forms the network on the
     * configured channel/PAN, registers the µBabel cluster + listeners, and
     * waits for the coordinator node to appear in the node table.
     *
     * <p>Does <em>not</em> open the network for joining — call
     * {@link #permitJoin(int)} for that once you are ready to accept devices.
     */
    public void init() throws Exception {
        port = new ZigBeeSerialPort(
                cfg.serialPort, cfg.serialBaud, FlowControl.FLOWCONTROL_OUT_NONE);

        dongle = new ZigBeeDongleEzsp(port);
        // Allow unsecured joins at the EZSP level before the stack applies
        // its own policy.
        dongle.updateDefaultPolicy(EzspPolicyId.EZSP_TRUST_CENTER_POLICY,
                                   EzspDecisionId.EZSP_ALLOW_JOINS);

        manager = new ZigBeeNetworkManager(dongle);
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
            throw new IllegalStateException(
                    "Failed to initialize ZigBee stack: " + status);
        }

        // Capture the dongle's IEEE address so we can identify the coordinator
        // node object later (the stack does not reliably populate NWK=0000 on
        // it).
        coordinatorIeee = dongle.getIeeeAddress();

        configureTransport();
        configureNetwork();
        registerListeners();

        status = manager.startup(true);
        if (status != ZigBeeStatus.SUCCESS) {
            throw new IllegalStateException(
                    "Failed to start ZigBee network: " + status);
        }

        // The coordinator node is added asynchronously during startup. Poll
        // briefly so post-startup calls (e.g. permitJoin) see it in the node
        // table.
        if (!waitForCoordinator()) {
            throw new IllegalStateException(
                    "Coordinator node never registered after startup");
        }
    }

    /**
     * Opens the network for joining for the given duration in seconds (clamp
     * at 254 for "indefinite open" in the Ember firmware). Tighten or remove
     * this in production deployments.
     */
    public void permitJoin(int seconds) {
        if (manager == null) {
            throw new IllegalStateException("init() must be called first");
        }
        manager.permitJoin(seconds);
    }

    /**
     * Sends a µBabel packet to the named end device by issuing a ZCL Write
     * Attributes against the {@code Data} attribute ({@link #ATTR_DATA}) of
     * the µBabel cluster on the end device's {@link #END_DEVICE_ENDPOINT}.
     *
     * <p>The destination node must already be known to the coordinator
     * (either via the manual bring-up path or via ZDO-driven discovery) and
     * must expose endpoint {@value #END_DEVICE_ENDPOINT} with cluster
     * {@value #UBABEL_CLUSTER_ID}. Throws {@link IllegalStateException} with
     * a descriptive message otherwise — call {@link #getKnownDevices()} to
     * check membership beforehand.
     *
     * <p>The returned {@link Future} completes asynchronously when the
     * end device acknowledges (or the transaction times out at the ZCL
     * layer). Callers may ignore the future for fire-and-forget semantics.
     *
     * @param destination the IEEE address of the target end device
     * @param packet      the µBabel packet to send; its byte form is wrapped
     *                    in a ZCL {@code ByteArray} as the attribute value
     * @return a future that completes with the ZCL transaction result
     */
    public Future<CommandResult> transmit(IeeeAddress destination,
                                          ZigBeePacket packet) {
        if (manager == null) {
            throw new IllegalStateException("init() must be called first");
        }
        if (destination == null) {
            throw new IllegalArgumentException(
                    "destination IEEE address must not be null");
        }
        if (packet == null) {
            throw new IllegalArgumentException("packet must not be null");
        }

        ZigBeeNode node = manager.getNode(destination);
        if (node == null) {
            throw new IllegalStateException(
                    "No known node with IEEE " + destination);
        }
        ZigBeeEndpoint ep = node.getEndpoint(END_DEVICE_ENDPOINT);
        if (ep == null) {
            throw new IllegalStateException(
                    "Node " + destination + " has no endpoint " +
                    END_DEVICE_ENDPOINT);
        }
        // The end-device hosts the attribute on its server side; from the
        // coordinator's view that's an input cluster. Fall back to the output
        // cluster in case the device descriptor advertised the cluster in the
        // client direction only.
        ZclCluster cluster = ep.getInputCluster(UBABEL_CLUSTER_ID);
        if (cluster == null) {
            cluster = ep.getOutputCluster(UBABEL_CLUSTER_ID);
        }
        if (cluster == null) {
            throw new IllegalStateException(
                    "Node " + destination + " endpoint " + END_DEVICE_ENDPOINT +
                    " has no cluster 0x" +
                    Integer.toHexString(UBABEL_CLUSTER_ID));
        }

        byte[] wire = packet.toBytes();
        if (wire.length > MAX_PACKET_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "ZigBee packet too large for the ZBDongle-E transport: " +
                    wire.length + " bytes on the wire (max " +
                    MAX_PACKET_SIZE_BYTES + " — i.e. " +
                    MAX_PAYLOAD_SIZE_BYTES + " bytes of payload)");
        }
        return cluster.writeAttribute(ATTR_DATA, ZclDataType.OCTET_STRING,
                                      new ByteArray(wire));
    }

    /** Closes the network manager and releases the serial port. */
    public void stop() {
        if (manager != null) {
            try {
                manager.shutdown();
            } catch (Exception ignored) {
            }
        }
        if (port != null) {
            try {
                port.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Registers a callback invoked for every µBabel data packet received from
     * an end device. The callback runs on the zsmartsystems command-listener
     * thread; consumers must be thread-safe (or hand off to their own queue).
     * Passing {@code null} clears the handler and restores the default
     * {@code System.out} dump behaviour.
     */
    public void setPacketHandler(
            BiConsumer<IeeeAddress, ZigBeePacket> handler) {
        this.packetHandler = handler;
    }

    /**
     * Registers a callback invoked for every µBabel heartbeat counter
     * received from an end device. Same threading rules as
     * {@link #setPacketHandler(BiConsumer)}.
     */
    public void setHeartbeatHandler(
            BiConsumer<IeeeAddress, Integer> handler) {
        this.heartbeatHandler = handler;
    }

    public ZigBeeConfig getConfig() { return cfg; }

    public IeeeAddress getCoordinatorIeee() { return coordinatorIeee; }

    /** Returns the last-known per-device state, or {@code null} if unseen. */
    public DeviceState getDeviceState(IeeeAddress ieee) {
        return deviceStates.get(ieee);
    }

    /** Snapshot of the IEEE addresses currently tracked by the coordinator. */
    public Collection<IeeeAddress> getKnownDevices() {
        return Collections.unmodifiableCollection(deviceStates.keySet());
    }

    /**
     * Cross-platform best-effort discovery of the Ember EZSP USB dongle's
     * device-node path — shorthand for {@link SerialPortDiscovery#autoDiscover()}.
     *
     * <p>Returns a path suitable for
     * {@link ZigBeeConfig.Builder#serialPort(String)} when exactly one
     * dongle is plugged in. Throws {@link IllegalStateException} (with a
     * descriptive list of what was seen) when zero or multiple candidates
     * are found — it intentionally never guesses. See
     * {@link SerialPortDiscovery} for the per-OS filtering details and for
     * {@link SerialPortDiscovery#listCandidates()} when you want to drive
     * your own selection UI.
     *
     * <p>Example:
     * <pre>{@code
     * ZigBeeConfig cfg = new ZigBeeConfig.Builder()
     *         .serialPort(ZigBeeCoordinator.autoDiscoverSerialPort())
     *         .build();
     * }</pre>
     */
    public static String autoDiscoverSerialPort() {
        return SerialPortDiscovery.autoDiscover();
    }

    // -------------------------------------------------------------------------
    // Setup helpers
    // -------------------------------------------------------------------------

    private void configureTransport() throws Exception {
        TransportConfig config = new TransportConfig();
        config.addOption(TransportConfigOption.TRUST_CENTRE_JOIN_MODE,
                         TrustCentreJoinMode.TC_JOIN_INSECURE);

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

        // Attempt to install the well-known join key (ZigBeeAlliance09). This
        // returns LIBRARY_NOT_PRESENT on some NCP firmware builds — safe to
        // ignore.
        if (cfg.allianceWellKnownKey != null) {
            ncp.addTransientLinkKey(new IeeeAddress("FFFFFFFFFFFFFFFF"),
                                    cfg.allianceWellKnownKey);
        }
    }

    private void configureNetwork() {
        manager.setZigBeeChannel(ZigBeeChannel.create(cfg.channel));
        manager.setZigBeePanId(cfg.panId);
        manager.setZigBeeExtendedPanId(new ExtendedPanId(cfg.extendedPanId));
        manager.setZigBeeNetworkKey(cfg.networkKey);
        manager.setZigBeeLinkKey(cfg.tcLinkKey);

        // ZigBeeBasicServerExtension responds to ZDO Basic cluster requests,
        // which some coordinators require during device discovery.
        ZigBeeNetworkExtension basicExt = new ZigBeeBasicServerExtension();
        manager.addExtension(basicExt);

        // Register the custom cluster so ClusterMatcher includes it in the
        // coordinator's endpoint descriptor sent during ZDO exchanges.
        manager.addSupportedClientCluster(UBABEL_CLUSTER_ID);
        manager.addSupportedServerCluster(UBABEL_CLUSTER_ID);
    }

    private void registerListeners() {
        manager.addNetworkStateListener(
                state -> System.out.println("Network state: " + state));

        // When a device announces itself, manually create a node entry and
        // trigger rediscovery. Necessary because the zsmartsystems stack will
        // not route incoming frames from unknown nodes when ZDO discovery
        // doesn't complete (the ESP firmware does not currently respond to
        // Active Endpoints / Simple Descriptor requests). Devices that DO
        // implement the full ZDO server side can opt out via
        // ZigBeeConfig.Builder.useManualBringup(false).
        manager.addAnnounceListener(new ZigBeeAnnounceListener() {
            @Override
            public void deviceStatusUpdate(ZigBeeNodeStatus status,
                                           Integer networkAddress,
                                           IeeeAddress ieeeAddress) {
                System.out.printf("Announce: %s addr=0x%04X ieee=%s%n", status,
                                  networkAddress, ieeeAddress);
                if (cfg.useManualBringup &&
                    status == ZigBeeNodeStatus.UNSECURED_JOIN) {
                    ZigBeeNode node = new ZigBeeNode(manager, ieeeAddress);
                    node.setNetworkAddress(networkAddress);
                    manager.updateNode(node);
                }
            }
        });

        manager.addNetworkNodeListener(new ZigBeeNetworkNodeListener() {
            @Override
            public void nodeAdded(ZigBeeNode node) {
                System.out.println("Node added: " + node.getIeeeAddress());
                if (node.getIeeeAddress().equals(coordinatorIeee)) {
                    if (cfg.useManualBringup) {
                        registerCoordinatorEndpoint(node);
                    }
                } else if (cfg.useManualBringup) {
                    registerEndDeviceEndpoint(node);
                }
            }

            @Override
            public void nodeUpdated(ZigBeeNode node) {
                if (!deviceStates.containsKey(node.getIeeeAddress()) &&
                    node.getEndpoint(END_DEVICE_ENDPOINT) != null) {
                    System.out.println(
                            "Node updated (already has ED endpoint): " +
                            node.getIeeeAddress());
                }
            }

            @Override
            public void nodeRemoved(ZigBeeNode node) {
                deviceStates.remove(node.getIeeeAddress());
                System.out.println("Node removed: " + node.getIeeeAddress());
            }
        });

        // Global command listener — intercepts WriteAttributesCommand directly
        // because the coordinator node's NWK address isn't populated by the
        // stack, which breaks cluster-level dispatch.
        manager.addCommandListener(command -> {
            if (!(command instanceof WriteAttributesCommand))
                return;
            WriteAttributesCommand writeCmd = (WriteAttributesCommand) command;
            if (writeCmd.getClusterId() != UBABEL_CLUSTER_ID)
                return;
            handleWriteAttributes(writeCmd);
        });
    }

    // -------------------------------------------------------------------------
    // Endpoint registration
    // -------------------------------------------------------------------------

    private void registerCoordinatorEndpoint(ZigBeeNode node) {
        if (node.getEndpoint(COORDINATOR_ENDPOINT) != null)
            return;

        ZigBeeEndpoint ep = new ZigBeeEndpoint(node, COORDINATOR_ENDPOINT);
        ep.setProfileId(HA_PROFILE_ID);
        ep.setDeviceId(0x0000);

        ZclCluster cluster =
                new ZclCluster(ep, UBABEL_CLUSTER_ID, "uBabel") {};
        cluster.addLocalAttributes(ubabelAttributes(cluster));
        ep.addInputCluster(cluster);
        ep.addOutputCluster(cluster);
        node.addEndpoint(ep);

        System.out.println(
                "Coordinator endpoint registered: " + node.getIeeeAddress());
    }

    private void registerEndDeviceEndpoint(ZigBeeNode node) {
        if (node.getEndpoint(END_DEVICE_ENDPOINT) != null)
            return;

        ZigBeeEndpoint ep = new ZigBeeEndpoint(node, END_DEVICE_ENDPOINT);
        ep.setProfileId(HA_PROFILE_ID);
        ep.setDeviceId(cfg.endDeviceId);

        ZclCluster cluster =
                new ZclCluster(ep, UBABEL_CLUSTER_ID, "uBabel") {};
        cluster.addAttributes(ubabelAttributes(cluster));
        ep.addInputCluster(cluster);
        ep.addOutputCluster(cluster);
        node.addEndpoint(ep);

        System.out.println(
                "End device endpoint registered: " + node.getIeeeAddress());
    }

    private static Set<ZclAttribute> ubabelAttributes(ZclCluster cluster) {
        return Set.of(
                new ZclAttribute(cluster, ATTR_DATA, "Data",
                                 ZclDataType.OCTET_STRING, false, true,
                                 true, false),
                new ZclAttribute(cluster, ATTR_HEARTBEAT, "Heartbeat",
                                 ZclDataType.UNSIGNED_16_BIT_INTEGER,
                                 false, true, true, false));
    }

    // -------------------------------------------------------------------------
    // Packet handling
    // -------------------------------------------------------------------------

    private void handleWriteAttributes(WriteAttributesCommand cmd) {
        ZigBeeNode sourceNode =
                manager.getNode(cmd.getSourceAddress().getAddress());
        if (sourceNode == null) {
            System.err.printf(
                    "WriteAttributes from unknown source NWK=0x%04X — dropped%n",
                    cmd.getSourceAddress().getAddress());
            return;
        }
        IeeeAddress ieee = sourceNode.getIeeeAddress();

        for (WriteAttributeRecord record : cmd.getRecords()) {
            int attrId = record.getAttributeIdentifier();
            switch (attrId) {
            case ATTR_DATA -> {
                byte[] raw = ((ByteArray) record.getAttributeValue()).get();
                handleDataPacket(raw, ieee);
            }
            case ATTR_HEARTBEAT -> {
                int counter = ((Number) record.getAttributeValue()).intValue();
                handleHeartbeat(counter, ieee);
            }
            // The µBabel ESP firmware also defines 0x0001 SENSOR_READING,
            // 0x0002 COMMAND, 0x0005 DISCOVERY, 0x0006 DISCOVERY_REQ on the
            // same cluster. Surface them so an interop gap is visible rather
            // than silently dropped.
            default -> System.err.printf(
                    "WriteAttributes from %s: unhandled attr 0x%04X " +
                    "(value type=%s) — dropped%n",
                    ieee, attrId,
                    record.getAttributeValue() == null
                            ? "null"
                            : record.getAttributeValue().getClass().getSimpleName());
            }
        }
    }

    private void handleDataPacket(byte[] raw, IeeeAddress ieee) {
        ZigBeePacket packet = ZigBeePacket.fromBytes(raw);
        if (packet == null) {
            System.err.printf(
                    "Packet from %s: malformed (len=%d) — dropped%n", ieee,
                    raw == null ? 0 : raw.length);
            return;
        }

        DeviceState state =
                deviceStates.computeIfAbsent(ieee, k -> new DeviceState());
        state.lastId = packet.getId();
        state.lastVal = packet.getVal();
        state.lastPayload = packet.getPayloadAsString();

        BiConsumer<IeeeAddress, ZigBeePacket> h = this.packetHandler;
        if (h != null) {
            try {
                h.accept(ieee, packet);
            } catch (Exception e) {
                System.err.println("ZigBee packet handler threw: " + e);
                e.printStackTrace();
            }
        } else {
            System.out.printf(
                    "Packet from %s: id=%d val=%d payload='%s'%n", ieee,
                    packet.getId(), packet.getVal(),
                    packet.getPayloadAsString());
        }
    }

    private void handleHeartbeat(int counter, IeeeAddress ieee) {
        DeviceState state =
                deviceStates.computeIfAbsent(ieee, k -> new DeviceState());
        state.heartbeatCounter = counter;

        BiConsumer<IeeeAddress, Integer> h = this.heartbeatHandler;
        if (h != null) {
            try {
                h.accept(ieee, counter);
            } catch (Exception e) {
                System.err.println("ZigBee heartbeat handler threw: " + e);
                e.printStackTrace();
            }
        } else {
            System.out.printf("Heartbeat from %s: counter=%d%n", ieee, counter);
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private boolean waitForCoordinator() throws InterruptedException {
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

    /** Last-known data and heartbeat counter reported by a given device. */
    public static class DeviceState {
        private volatile int lastId;
        private volatile int lastVal;
        private volatile String lastPayload = "";
        private volatile int heartbeatCounter;

        public int getLastId() { return lastId; }
        public int getLastVal() { return lastVal; }
        public String getLastPayload() { return lastPayload; }
        public int getHeartbeatCounter() { return heartbeatCounter; }

        @Override
        public String toString() {
            return String.format(
                    "DeviceState[id=%d, val=%d, heartbeat=%d, payload='%s']",
                    lastId, lastVal, heartbeatCounter, lastPayload);
        }
    }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /**
     * Network and radio configuration for a {@link ZigBeeCoordinator}.
     * Mirrors {@code LoRaHAT.E22Config} in shape: an immutable value object
     * built via {@link Builder}. Defaults match the values previously
     * hard-coded in the smoke-test entry point.
     */
    public static class ZigBeeConfig {

        /** Well-known ZigBee Alliance default join key ("ZigBeeAlliance09"). */
        public static final ZigBeeKey KEY_ALLIANCE09 = new ZigBeeKey(
                new int[] {0x5A, 0x69, 0x67, 0x42, 0x65, 0x65, 0x41, 0x6C,
                           0x6C, 0x69, 0x61, 0x6E, 0x63, 0x65, 0x30, 0x39});

        private final String serialPort;
        private final int serialBaud;
        private final int channel;
        private final int panId;
        private final String extendedPanId;
        private final ZigBeeKey networkKey;
        private final ZigBeeKey tcLinkKey;
        private final ZigBeeKey allianceWellKnownKey;
        private final int endDeviceId;
        private final boolean useManualBringup;

        private ZigBeeConfig(Builder b) {
            this.serialPort = b.serialPort;
            this.serialBaud = b.serialBaud;
            this.channel = b.channel;
            this.panId = b.panId;
            this.extendedPanId = b.extendedPanId;
            this.networkKey = b.networkKey;
            this.tcLinkKey = b.tcLinkKey;
            this.allianceWellKnownKey = b.allianceWellKnownKey;
            this.endDeviceId = b.endDeviceId;
            this.useManualBringup = b.useManualBringup;
        }

        public String getSerialPort() { return serialPort; }
        public int getSerialBaud() { return serialBaud; }
        public int getChannel() { return channel; }
        public int getPanId() { return panId; }
        public String getExtendedPanId() { return extendedPanId; }
        public ZigBeeKey getNetworkKey() { return networkKey; }
        public ZigBeeKey getTcLinkKey() { return tcLinkKey; }
        public ZigBeeKey getAllianceWellKnownKey() {
            return allianceWellKnownKey;
        }
        public int getEndDeviceId() { return endDeviceId; }
        public boolean isUseManualBringup() { return useManualBringup; }

        @Override
        public String toString() {
            return String.format(
                    "ZigBeeConfig[port=%s baud=%d channel=%d panId=0x%04X " +
                    "epanId=%s endDeviceId=0x%04X manualBringup=%s]",
                    serialPort, serialBaud, channel, panId, extendedPanId,
                    endDeviceId, useManualBringup);
        }

        public static class Builder {
            // Defaults from the original smoke-test bring-up. The factory and
            // trust-centre link keys carry no widely-known semantics — replace
            // with your own production keys before deploying.
            private String serialPort;
            private int serialBaud = 115200;
            private int channel = 13;
            private int panId = 0xE5F2;
            // 16 hex characters / 64 bits. Strings longer than 16 chars are
            // silently truncated by zsmartsystems' BigInteger-based parser, so
            // we validate length explicitly in the builder.
            private String extendedPanId = "1122334455667788";
            // 16-byte preconfigured network key. The coordinator chooses this
            // value and distributes it to joining devices encrypted with the
            // trust-centre link key during the secured-join handshake, so the
            // ESP firmware does *not* need to carry the same constant. Only
            // the TC link key below must match the value compiled into the
            // ESP firmware (ubabel_zb_proto.c).
            private ZigBeeKey networkKey = new ZigBeeKey(new int[] {
                    0x01, 0x03, 0x05, 0x07, 0x09, 0x0B, 0x0D, 0x0F,
                    0x00, 0x02, 0x04, 0x06, 0x08, 0x0A, 0x0C, 0x0E});
            // 16-byte trust-centre link key. THIS one must match the ESP
            // firmware (ubabel_zb_proto.c carries the same 16 bytes) — both
            // sides preshare it; the coordinator uses it to encrypt the
            // network-key delivery during the secured-join handshake.
            private ZigBeeKey tcLinkKey = new ZigBeeKey(new int[] {
                    0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45, 0x67, 0x89,
                    0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45, 0x67, 0x89});
            private ZigBeeKey allianceWellKnownKey = KEY_ALLIANCE09;
            private int endDeviceId = DEFAULT_END_DEVICE_ID;
            private boolean useManualBringup = true;

            public Builder serialPort(String path) {
                this.serialPort = path;
                return this;
            }

            public Builder serialBaud(int baud) {
                this.serialBaud = baud;
                return this;
            }

            public Builder channel(int channel) {
                this.channel = channel;
                return this;
            }

            public Builder panId(int panId) {
                this.panId = panId & 0xFFFF;
                return this;
            }

            public Builder extendedPanId(String hex) {
                if (hex == null || hex.length() != 16) {
                    throw new IllegalArgumentException(
                            "extendedPanId must be exactly 16 hex characters " +
                            "(got '" + hex + "')");
                }
                this.extendedPanId = hex;
                return this;
            }

            public Builder networkKey(ZigBeeKey key) {
                this.networkKey = key;
                return this;
            }

            public Builder tcLinkKey(ZigBeeKey key) {
                this.tcLinkKey = key;
                return this;
            }

            /**
             * Override the well-known join key offered to the NCP via
             * {@code addTransientLinkKey}. Pass {@code null} to skip the call
             * entirely (useful on NCP builds that return
             * {@code LIBRARY_NOT_PRESENT}).
             */
            public Builder allianceWellKnownKey(ZigBeeKey key) {
                this.allianceWellKnownKey = key;
                return this;
            }

            /**
             * HA device id assigned to the end-device endpoint when manual
             * bring-up is enabled. Must match the {@code deviceId} field in
             * the ESP firmware's simple-descriptor declaration. Defaults to
             * {@link ZigBeeCoordinator#DEFAULT_END_DEVICE_ID} (0xFFF2), which
             * is what the µBabel ESP reference firmware uses.
             */
            public Builder endDeviceId(int deviceId) {
                this.endDeviceId = deviceId & 0xFFFF;
                return this;
            }

            /**
             * Whether the coordinator should manually create node and
             * endpoint objects when a device announces itself. Defaults to
             * {@code true} to preserve the historical µBabel ESP bring-up
             * path verbatim.
             *
             * <p>The current µBabel ESP firmware <em>does</em> call
             * {@code esp_zb_device_register()} with a populated cluster list,
             * and the default ESP-Zigbee stack auto-responds to ZDO Active
             * Endpoints / Simple Descriptor requests — so setting this to
             * {@code false} and letting zsmartsystems auto-populate the node
             * via standard ZDO discovery is expected to work in practice.
             * Try it once you can test against real hardware; if discovery
             * doesn't complete, flip back to {@code true} and the manual
             * workaround takes over.
             */
            public Builder useManualBringup(boolean enabled) {
                this.useManualBringup = enabled;
                return this;
            }

            public ZigBeeConfig build() {
                return new ZigBeeConfig(this);
            }
        }
    }
}
