package zigbee;

import com.zsmartsystems.zigbee.ExtendedPanId;
import com.zsmartsystems.zigbee.IeeeAddress;
import com.zsmartsystems.zigbee.ZigBeeAnnounceListener;
import com.zsmartsystems.zigbee.ZigBeeBroadcastDestination;
import com.zsmartsystems.zigbee.ZigBeeChannel;
import com.zsmartsystems.zigbee.ZigBeeEndpoint;
import com.zsmartsystems.zigbee.ZigBeeEndpointAddress;
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
import com.zsmartsystems.zigbee.zcl.ZclCluster;
import com.zsmartsystems.zigbee.zcl.ZclCommand;
import com.zsmartsystems.zigbee.zcl.clusters.general.WriteAttributesCommand;
import com.zsmartsystems.zigbee.zcl.field.ByteArray;
import com.zsmartsystems.zigbee.zcl.field.WriteAttributeRecord;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import com.zsmartsystems.zigbee.CommandResult;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * ZigBee coordinator on top of the zsmartsystems stack with an Ember (EZSP)
 * USB dongle backend. Forms a ZigBee Home Automation network on a fixed
 * channel/PAN, registers the µBabel vendor cluster, and surfaces incoming
 * µBabel packets and heartbeats to caller-supplied handlers.
 *
 * <h2>Wire contract (since 0.5.0 — must match the 2026-06 µBabel firmware)</h2>
 * <ul>
 *   <li><b>DATA and DISCOVERY are ZCL cluster-specific custom commands</b>
 *   ({@link UbabelDataCommand} {@code 0x0003} / {@link UbabelDiscoveryCommand}
 *   {@code 0x0005}) on cluster {@link #UBABEL_CLUSTER_ID 0xFF00}, HA profile,
 *   direction CLIENT_TO_SERVER, <em>bidirectional</em> (both the ESP and this
 *   coordinator send them). The single command field is an OCTET_STRING-typed
 *   payload — {@code [len:u8][bytes...]} on the wire. The change exists so
 *   the receiver learns the sender's short address (the ESP's write-attribute
 *   callback carried no source address), letting µBabel key ZigBee
 *   reassembly per sender.</li>
 *   <li><b>HEARTBEAT is unchanged</b> — still a ZCL Write Attributes on
 *   {@link #ATTR_HEARTBEAT 0x0004}. (The current µBabel firmware has no
 *   heartbeat sender — {@code zb_send_heartbeat} was removed — so this RX
 *   path is dormant until the firmware regains one.)</li>
 *   <li><b>Legacy DATA/DISCOVERY attribute writes</b> ({@code 0x0003}/
 *   {@code 0x0005}) are still accepted inbound for backward compatibility,
 *   but pre-2026-06 firmware no longer parses them outbound — the coordinator
 *   only ever transmits custom commands.</li>
 * </ul>
 *
 * <p>Wire constants ({@link #HA_PROFILE_ID}, the two endpoints, the cluster
 * id, the command/attribute ids and the end-device ID configured via
 * {@link ZigBeeConfig.Builder#endDeviceId(int)} — default
 * {@link #DEFAULT_END_DEVICE_ID}) must stay in sync with
 * {@code uBabel/components/zigbee/zb_stack.h} on the ESP / Pico side.
 */
public class ZigBeeCoordinator {

    public static final int HA_PROFILE_ID = 0x0104;
    public static final int COORDINATOR_ENDPOINT = 1;
    public static final int END_DEVICE_ENDPOINT = 10;
    /** µBabel vendor cluster ({@code UBABEL_CUSTOM_CLUSTER_ID} in
     *  {@code zb_stack.h}). DATA/DISCOVERY custom commands and the HEARTBEAT
     *  attribute all live on it. */
    public static final int UBABEL_CLUSTER_ID = 0xFF00;
    /** DATA id ({@code 0x0003}). Primarily the custom-command id
     *  ({@link UbabelDataCommand#COMMAND_ID}); doubles as the legacy DATA
     *  attribute id still accepted inbound. */
    public static final int ATTR_DATA = 0x0003;
    /** HEARTBEAT attribute (UINT16 counter) — the one µBabel value still
     *  carried as a ZCL Write Attributes. Dormant on the firmware side (no
     *  heartbeat sender at present), but the RX path stays wired. */
    public static final int ATTR_HEARTBEAT = 0x0004;
    /** DISCOVERY id ({@code 0x0005}). Primarily the custom-command id
     *  ({@link UbabelDiscoveryCommand#COMMAND_ID}); doubles as the legacy
     *  DISCOVERY attribute id still accepted inbound. */
    public static final int ATTR_DISCOVERY = 0x0005;

    /** Max practical join-window duration (s). ZigBee 3.0 deprecated 0xFF "permanent". */
    public static final int PERMIT_JOIN_MAX_SECONDS = 254;
    /** Re-permit cadence for {@link #permitJoinPermanently()} — a little under the window. */
    private static final int PERMIT_JOIN_REFRESH_SECONDS = 240;
    /** Default HA device id advertised by the ESP firmware. Override via
     *  {@link ZigBeeConfig.Builder#endDeviceId(int)} if your firmware uses a
     *  different value. */
    public static final int DEFAULT_END_DEVICE_ID = 0xFFF2;

    /** Maximum on-the-wire size of the OCTET_STRING value (a wrapped
     *  {@code ubabel_packet_t} or fragment frame) carried in the µBabel
     *  DATA/DISCOVERY custom command. The ZBDongle-E ZCL transport caps the
     *  value at 128 bytes; subtracting 6 bytes of ZCL framing overhead and
     *  1 byte for the OCTET_STRING length prefix leaves 121 bytes for the
     *  raw value (see {@code ZB_MAX_PACKET_SIZE} in {@code ubabel_zb_proto.h}
     *  on the µBabel side — the firmware enforces the same cap on its
     *  outgoing path). {@link #transmit} enforces this against
     *  {@code packet.getPayload()}. */
    public static final int MAX_PACKET_SIZE_BYTES = 121;

    /** Historical payload budget — {@link #MAX_PACKET_SIZE_BYTES} minus the
     *  5 bytes the (now-scrapped) {@code ubabel_zb_packet_t} header once cost.
     *  Retained as a conservative application-payload guideline; the transport
     *  cap actually enforced on the wire is {@link #MAX_PACKET_SIZE_BYTES}. */
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

    /** Daemon scheduler that keeps the join window open for {@link #permitJoinPermanently()}; null until enabled. */
    private ScheduledExecutorService permitJoinScheduler;

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

        // Make sure every node already present in the node table (e.g.
        // restored from a persistent data store, or added before the
        // listeners were registered) carries the command-aware µBabel
        // cluster — newly appearing nodes are covered by the node listener.
        for (ZigBeeNode node : manager.getNodes()) {
            attachUbabelClusters(node);
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
     * Keeps the network open for joining <em>indefinitely</em>. ZigBee 3.0 deprecated
     * the {@code 0xFF} "permanent" duration and the Ember stack caps a single window
     * at ~{@value #PERMIT_JOIN_MAX_SECONDS}s, so this re-issues
     * {@code permitJoin(}{@value #PERMIT_JOIN_MAX_SECONDS}{@code )} on a periodic daemon
     * timer rather than relying on one permanent window. (Note: {@code permitJoin(0)}
     * would <em>disable</em> joining, not make it permanent.) Idempotent. An always-open
     * join window is a security trade-off — gate it behind config and prefer closing it
     * once the fleet is provisioned.
     */
    public synchronized void permitJoinPermanently() {
        if (manager == null) {
            throw new IllegalStateException("init() must be called first");
        }
        manager.permitJoin(PERMIT_JOIN_MAX_SECONDS);
        if (permitJoinScheduler == null) {
            permitJoinScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "zb-permit-join-refresh");
                t.setDaemon(true);
                return t;
            });
            permitJoinScheduler.scheduleAtFixedRate(() -> {
                try {
                    manager.permitJoin(PERMIT_JOIN_MAX_SECONDS);
                } catch (Exception e) {
                    System.err.println("permitJoin refresh failed: " + e);
                }
            }, PERMIT_JOIN_REFRESH_SECONDS, PERMIT_JOIN_REFRESH_SECONDS,
               TimeUnit.SECONDS);
        }
    }

    /**
     * Sends a µBabel packet to the named end device as a µBabel DATA custom
     * command ({@link UbabelDataCommand}, id {@code 0x0003}) on the µBabel
     * cluster of the end device's {@link #END_DEVICE_ENDPOINT}. The packet
     * bytes ride as the command's OCTET_STRING payload
     * ({@code [len:u8][bytes...]} on the wire) — the form the 2026-06 µBabel
     * firmware's {@code zb_custom_cmd_handler} expects (it no longer parses
     * attribute writes for DATA).
     *
     * <p>The destination node must already be known to the coordinator
     * (either via the manual bring-up path or via ZDO-driven discovery) and
     * must expose endpoint {@value #END_DEVICE_ENDPOINT}. Throws
     * {@link IllegalStateException} with a descriptive message otherwise —
     * call {@link #getKnownDevices()} to check membership beforehand. The
     * command-aware {@link UbabelZclCluster} is attached on demand if the
     * endpoint does not carry it yet.
     *
     * <p>The returned {@link Future} completes asynchronously when the end
     * device's ZCL Default Response arrives (the command does not disable the
     * default response, so it doubles as the delivery acknowledgement —
     * mirroring the Write Attributes Response of the pre-0.5.0 transport) or
     * when the transaction times out at the ZCL layer. Callers may ignore the
     * future for fire-and-forget semantics.
     *
     * @param destination the IEEE address of the target end device
     * @param packet      the µBabel packet to send; its byte form is wrapped
     *                    in a ZCL {@code ByteArray} as the command payload
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
        // Idempotent — replaces any auto-instantiated ZclCustomCluster shell
        // with the command-aware µBabel cluster if a discovery race got here
        // first.
        attachUbabelClusters(ep);
        // Send through the *input*-cluster instance: it keeps the server-side
        // flag, so ZclCluster.sendCommand leaves the command direction at
        // CLIENT_TO_SERVER — the direction the ESP firmware accepts.
        ZclCluster cluster = ep.getInputCluster(UBABEL_CLUSTER_ID);
        if (!(cluster instanceof UbabelZclCluster ubabelCluster)) {
            throw new IllegalStateException(
                    "Node " + destination + " endpoint " + END_DEVICE_ENDPOINT +
                    " has no µBabel cluster 0x" +
                    Integer.toHexString(UBABEL_CLUSTER_ID) +
                    " (found " +
                    (cluster == null ? "none"
                                     : cluster.getClass().getSimpleName()) +
                    ")");
        }

        byte[] wire = packet.getPayload();
        if (wire.length > MAX_PACKET_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "ZigBee packet too large for the ZBDongle-E transport: " +
                    wire.length + " bytes on the wire (max " +
                    MAX_PACKET_SIZE_BYTES + " — i.e. " +
                    MAX_PAYLOAD_SIZE_BYTES + " bytes of payload)");
        }
        return ubabelCluster.sendData(new ByteArray(wire));
    }

    /**
     * NWK-layer broadcast of a {@link ZigBeePacket} to every joined node in
     * one-hop reach. The packet is delivered as a µBabel DATA custom command
     * ({@link UbabelDataCommand}) on {@link #END_DEVICE_ENDPOINT} of every
     * recipient. Convenience overload targeting
     * {@link ZigBeeBroadcastDestination#BROADCAST_ALL_DEVICES}.
     *
     * <p>Broadcast caveats:
     * <ul>
     *   <li>The ZigBee NWK layer does not deliver to sleepy end devices that
     *   are not currently awake (use {@code BROADCAST_RX_ON} to deliberately
     *   skip them, or {@code BROADCAST_ALL_DEVICES} which still depends on
     *   the device's poll cycle).</li>
     *   <li>Broadcasts are unacknowledged. The returned value indicates only
     *   that the NCP accepted the command for transmission — not that any
     *   peer received it.</li>
     *   <li>Joined nodes whose endpoint descriptor does not advertise the
     *   µBabel cluster will simply ignore the frame.</li>
     * </ul>
     *
     * @param packet the µBabel packet to broadcast
     * @return {@code true} if the NCP accepted the command for transmission
     * @throws IllegalArgumentException if the packet exceeds
     *         {@link #MAX_PACKET_SIZE_BYTES} on the wire
     * @throws IllegalStateException    if {@link #init()} has not been called
     */
    public boolean transmit(ZigBeePacket packet) {
        return transmit(ZigBeeBroadcastDestination.BROADCAST_ALL_DEVICES,
                        packet);
    }

    /**
     * NWK-layer broadcast of a {@link ZigBeePacket} to the given broadcast
     * scope. See the no-arg overload for caveats.
     *
     * @param scope  which set of nodes to address (all devices, rx-on-when-
     *               idle, routers-and-coordinator, …)
     * @param packet the µBabel packet to broadcast
     * @return {@code true} if the NCP accepted the command for transmission
     */
    public boolean transmit(ZigBeeBroadcastDestination scope,
                            ZigBeePacket packet) {
        if (manager == null) {
            throw new IllegalStateException("init() must be called first");
        }
        if (scope == null) {
            throw new IllegalArgumentException(
                    "broadcast scope must not be null");
        }
        if (packet == null) {
            throw new IllegalArgumentException("packet must not be null");
        }

        byte[] wire = packet.getPayload();
        if (wire.length > MAX_PACKET_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "ZigBee packet too large for the ZBDongle-E transport: " +
                    wire.length + " bytes on the wire (max " +
                    MAX_PACKET_SIZE_BYTES + " — i.e. " +
                    MAX_PAYLOAD_SIZE_BYTES + " bytes of payload)");
        }

        // The cluster-level send path is unicast-only (it resolves a remote
        // endpoint+cluster pair on a known node), so we construct the µBabel
        // DATA custom command manually and dispatch it through the manager's
        // sendCommand() with a broadcast NWK destination — the same mechanism
        // the pre-0.5.0 hand-built WriteAttributesCommand used. Broadcasts
        // are unacknowledged at the NWK layer (no transaction to track), so
        // the default response is disabled: a response storm from every
        // recipient would serve nothing.
        UbabelDataCommand cmd = new UbabelDataCommand(new ByteArray(wire));
        cmd.setDestinationAddress(
                new ZigBeeEndpointAddress(scope.getKey(), END_DEVICE_ENDPOINT));
        cmd.setDisableDefaultResponse(true);

        return manager.sendCommand(cmd);
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
                // Idempotent; covers nodes that arrive with endpoints already
                // populated (e.g. restored from a data store). The manual
                // bring-up registrations below attach on the endpoints they
                // create themselves.
                attachUbabelClusters(node);
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
                // ZDO-driven discovery (useManualBringup=false) populates
                // endpoints asynchronously — swap any auto-instantiated
                // ZclCustomCluster shells for the command-aware µBabel
                // cluster as soon as the endpoints appear. Idempotent.
                attachUbabelClusters(node);
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

        // Global command listener — intercepts the parsed commands directly
        // because the coordinator node's NWK address isn't populated by the
        // stack, which breaks cluster-level dispatch. The µBabel custom
        // commands (DATA 0x0003 / DISCOVERY 0x0005) are the primary inbound
        // path since the 2026-06 firmware; WriteAttributesCommand remains for
        // the HEARTBEAT attribute (0x0004) and for legacy DATA/DISCOVERY
        // writes from pre-2026-06 firmware.
        manager.addCommandListener(command -> {
            if (command instanceof UbabelDataCommand dataCmd) {
                handleUbabelCommand(dataCmd, dataCmd.getPayload());
            } else if (command instanceof UbabelDiscoveryCommand discoveryCmd) {
                handleUbabelCommand(discoveryCmd, discoveryCmd.getPayload());
            } else if (command instanceof WriteAttributesCommand writeCmd
                       && writeCmd.getClusterId() == UBABEL_CLUSTER_ID) {
                handleWriteAttributes(writeCmd);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Endpoint registration
    // -------------------------------------------------------------------------

    /**
     * Manually registers the coordinator's own endpoint
     * ({@value #COORDINATOR_ENDPOINT}) carrying the µBabel cluster. Used on
     * the manual bring-up path ({@code useManualBringup=true}) so the local
     * node mirrors the endpoint layout the ESP firmware expects to see.
     */
    private void registerCoordinatorEndpoint(ZigBeeNode node) {
        if (node.getEndpoint(COORDINATOR_ENDPOINT) != null)
            return;

        ZigBeeEndpoint ep = new ZigBeeEndpoint(node, COORDINATOR_ENDPOINT);
        ep.setProfileId(HA_PROFILE_ID);
        ep.setDeviceId(0x0000);
        attachUbabelClusters(ep);
        node.addEndpoint(ep);

        System.out.println(
                "Coordinator endpoint registered: " + node.getIeeeAddress());
    }

    /**
     * Manually registers a joined end device's endpoint
     * ({@value #END_DEVICE_ENDPOINT}) carrying the µBabel cluster. Used on
     * the manual bring-up path when a device announces itself but ZDO
     * discovery does not populate the node (see
     * {@link ZigBeeConfig.Builder#useManualBringup(boolean)}).
     */
    private void registerEndDeviceEndpoint(ZigBeeNode node) {
        if (node.getEndpoint(END_DEVICE_ENDPOINT) != null)
            return;

        ZigBeeEndpoint ep = new ZigBeeEndpoint(node, END_DEVICE_ENDPOINT);
        ep.setProfileId(HA_PROFILE_ID);
        ep.setDeviceId(cfg.endDeviceId);
        attachUbabelClusters(ep);
        node.addEndpoint(ep);

        System.out.println(
                "End device endpoint registered: " + node.getIeeeAddress());
    }

    /**
     * Ensures every endpoint of the given node carries the command-aware
     * µBabel cluster ({@link UbabelZclCluster}) — see the
     * {@link #attachUbabelClusters(ZigBeeEndpoint) endpoint overload} for
     * why this is required. Safe to call repeatedly and from the stack's
     * listener threads.
     */
    private void attachUbabelClusters(ZigBeeNode node) {
        for (ZigBeeEndpoint ep : node.getEndpoints()) {
            attachUbabelClusters(ep);
        }
    }

    /**
     * Ensures the given endpoint carries a {@link UbabelZclCluster} as both
     * its input and its output cluster {@value #UBABEL_CLUSTER_ID}.
     *
     * <p>This is what makes the inbound custom-command path work at all: the
     * zsmartsystems receive pipeline resolves an incoming cluster-specific
     * frame against the <em>sender</em> endpoint's cluster instance
     * (CLIENT_TO_SERVER → {@code getOutputCluster(...).getCommandFromId(...)},
     * SERVER_TO_CLIENT → {@code getInputCluster(...).getResponseFromId(...)}),
     * and the placeholder the stack auto-instantiates for unknown clusters
     * ({@code ZclCustomCluster}) has <em>empty</em> command maps — every
     * µBabel command frame would be dropped with a FAILURE default response.
     * Replacing the placeholder is the supported extension point:
     * {@code ZigBeeEndpoint.addInputCluster}/{@code addOutputCluster}
     * deliberately replace an existing instance when it is a
     * {@code ZclCustomCluster}.
     *
     * <p>Two <em>separate</em> instances are registered because
     * {@code addOutputCluster} flags its instance client-side, which would
     * make {@code ZclCluster.sendCommand} rewrite the TX direction to
     * SERVER_TO_CLIENT; the input-side (server) instance is the one
     * {@link #transmit(IeeeAddress, ZigBeePacket)} sends through.
     *
     * <p>Idempotent — endpoints already carrying {@link UbabelZclCluster}
     * instances are left untouched. A failed replacement (an unexpected
     * non-replaceable cluster instance already present) is loudly logged
     * rather than thrown, since this runs on stack listener threads.
     */
    private void attachUbabelClusters(ZigBeeEndpoint ep) {
        if (!(ep.getInputCluster(UBABEL_CLUSTER_ID) instanceof UbabelZclCluster)
                && !ep.addInputCluster(new UbabelZclCluster(ep))) {
            System.err.printf(
                    "Could not attach µBabel input cluster on %s endpoint %d " +
                    "(non-replaceable %s already present)%n",
                    ep.getIeeeAddress(), ep.getEndpointId(),
                    ep.getInputCluster(UBABEL_CLUSTER_ID)
                            .getClass().getSimpleName());
        }
        if (!(ep.getOutputCluster(UBABEL_CLUSTER_ID) instanceof UbabelZclCluster)
                && !ep.addOutputCluster(new UbabelZclCluster(ep))) {
            System.err.printf(
                    "Could not attach µBabel output cluster on %s endpoint %d " +
                    "(non-replaceable %s already present)%n",
                    ep.getIeeeAddress(), ep.getEndpointId(),
                    ep.getOutputCluster(UBABEL_CLUSTER_ID)
                            .getClass().getSimpleName());
        }
    }

    // -------------------------------------------------------------------------
    // Packet handling
    // -------------------------------------------------------------------------

    /**
     * Inbound path for the µBabel custom commands ({@link UbabelDataCommand}
     * DATA {@code 0x0003} / {@link UbabelDiscoveryCommand} DISCOVERY
     * {@code 0x0005}) — the primary sensor traffic since the 2026-06 µBabel
     * firmware. Resolves the sender's IEEE address from the command's source
     * NWK address (the same lookup {@link #handleWriteAttributes} performs)
     * and feeds the raw OCTET_STRING payload into
     * {@link #handleWrappedPacket}, so custom commands and legacy attribute
     * writes surface identically through the public
     * {@code BiConsumer<IeeeAddress, ZigBeePacket>} handler.
     *
     * <p>The message kind (DATA / DISCOVERY / …) lives in the carried
     * {@code ubabel_packet_t.type} field, not in the ZCL command id, so both
     * commands are dispatched identically — mirroring how the two legacy
     * attributes were handled.
     *
     * @param cmd     the parsed µBabel command (used for sender resolution
     *                and diagnostics)
     * @param payload the command's OCTET_STRING payload — the raw wrapped
     *                {@code ubabel_packet_t} or fragment frame bytes (length
     *                prefix already stripped by the codec)
     */
    private void handleUbabelCommand(ZclCommand cmd, ByteArray payload) {
        ZigBeeNode sourceNode =
                manager.getNode(cmd.getSourceAddress().getAddress());
        if (sourceNode == null) {
            System.err.printf(
                    "%s from unknown source NWK=0x%04X — dropped%n",
                    cmd.getClass().getSimpleName(),
                    cmd.getSourceAddress().getAddress());
            return;
        }
        handleWrappedPacket(payload == null ? null : payload.get(),
                            sourceNode.getIeeeAddress());
    }

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
                handleWrappedPacket(raw, ieee);
            }
            case ATTR_HEARTBEAT -> {
                int counter = ((Number) record.getAttributeValue()).intValue();
                handleHeartbeat(counter, ieee);
            }
            case ATTR_DISCOVERY -> {
                byte[] raw = ((ByteArray) record.getAttributeValue()).get();
                handleWrappedPacket(raw, ieee);
            }
            // The µBabel ESP firmware also defines 0x0001 SENSOR_READING,
            // 0x0002 COMMAND, 0x0006 DISCOVERY_REQ on the same cluster.
            // Surface them so an interop gap is visible rather than silently
            // dropped.
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

    /**
     * Surfaces a µBabel packet received on the vendor cluster — regardless of
     * which ingress carried it: a DATA/DISCOVERY custom command (the primary
     * path since 0.5.0, via {@link #handleUbabelCommand}) or a legacy
     * DATA/DISCOVERY attribute write (via {@link #handleWriteAttributes}).
     *
     * <p>The OCTET_STRING value is the same wrapped form the LoRa side uses:
     * a {@code ubabel_packet_t} whose leading 2-byte {@code proto_id} is the
     * destination-protocol-id envelope (big-endian; {@code htons(1000)} for the
     * gateway's {@code SensorInboundProtocol}), followed by the packet body
     * ({@code recipient}/{@code sender}/{@code message_id}/{@code type}/
     * {@code payload_len}/{@code payload}) — or a fragment frame of one. The
     * message kind (DATA / DISCOVERY / …) lives in the packet's {@code type}
     * field, <em>not</em> in the ZCL command/attribute id — which is why every
     * ingress is surfaced identically.
     *
     * <p>The bytes are surfaced verbatim as a {@link ZigBeePacket} payload;
     * the {@code babel-zigbee-protocol} bridge strips the 2-byte
     * {@code destProto} envelope and routes the remainder, mirroring how the
     * LoRa side surfaces a received frame. The obsolete
     * {@code ubabel_zb_packet_t} ({@code id}/{@code val}/{@code payload_len})
     * framing is no longer parsed.
     */
    private void handleWrappedPacket(byte[] raw, IeeeAddress ieee) {
        if (raw == null) {
            System.err.printf("Packet from %s: null value — dropped%n", ieee);
            return;
        }
        ZigBeePacket packet = new ZigBeePacket.Builder().payload(raw).build();

        DeviceState state =
                deviceStates.computeIfAbsent(ieee, k -> new DeviceState());
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
            System.out.printf("Packet from %s: %d bytes (no handler)%n",
                              ieee, raw.length);
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

    /** Last-known packet payload and heartbeat counter reported by a device. */
    public static class DeviceState {
        private volatile String lastPayload = "";
        private volatile int heartbeatCounter;

        public String getLastPayload() { return lastPayload; }
        public int getHeartbeatCounter() { return heartbeatCounter; }

        @Override
        public String toString() {
            return String.format(
                    "DeviceState[heartbeat=%d, payload='%s']",
                    heartbeatCounter, lastPayload);
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
