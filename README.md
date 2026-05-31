# Babel ZigBee

A Java ZigBee coordinator for Raspberry Pi gateways, built on the [zsmartsystems ZigBee stack](https://github.com/zsmartsystems/com.zsmartsystems.zigbee) with an Ember (EZSP) USB dongle as the radio backend. It forms a ZigBee Home Automation network, registers a vendor-specific cluster, and exchanges µBabel packets with ESP32 / Raspberry Pi Pico end devices via ZCL Write Attribute commands.

The artifact is intended to back a future Babel protocol providing ZigBee connectivity to swarm-style applications on Raspberry Pi gateways. Today it is a self-contained coordinator — there is no Babel dependency yet.

**Group ID:** `pt.paradigmshift.iot`
**Artifact ID:** `babel-zigbee`
**Current version:** `0.2.0`
**Tested on:** Raspberry Pi 4 / 5 (and macOS for development) with a Silicon Labs Ember EZSP USB dongle.

---

## Hardware

| Item | Value |
|---|---|
| Radio module | Silicon Labs EM35x / EFR32 (Ember EZSP firmware) |
| Band | 2.4 GHz — ZigBee channel 13 by default |
| Serial transport | USB CDC @ 115200 baud, 8N1, no flow control |
| PAN ID | `0xE5F2` (default) |
| Extended PAN ID | `0x1122334455667788` (default — must be 16 hex chars) |
| Profile | ZigBee Home Automation (`0x0104`) |
| Trust centre policy | `TC_JOIN_INSECURE` (open joins; tighten for production) |

The coordinator opens the network for joining via `permitJoin(254)`, formed on a fixed channel/PAN with hard-coded network and trust-centre link keys.

### Serial device

The serial port is passed in via `ZigBeeConfig.Builder.serialPort(...)`. The library has no hardcoded default. On a Raspberry Pi the dongle typically enumerates as `/dev/ttyUSB0` (CP210x bridge) or `/dev/ttyACM0` (CDC); on macOS as `/dev/cu.usbserial-<serial>`; on Windows as `COMn`.

If you do not know the path up front, call the cross-platform auto-discovery helper:

```java
String port = ZigBeeCoordinator.autoDiscoverSerialPort();   // throws if 0 or >1 found
ZigBeeConfig cfg = new ZigBeeConfig.Builder()
        .serialPort(port)
        .build();
```

Auto-discovery is intentionally conservative — it returns a path only when exactly one candidate is found, and throws `IllegalStateException` (with a diagnostic listing of what was seen) for zero or multiple matches. Per-OS behaviour:

| OS | Strategy | Strength |
|---|---|---|
| **Linux** | Walks `/dev/serial/by-id/` and keeps entries matching `Silicon_Labs` / `CP210x` / `Sonoff` / `ITead` / `EFR32` / `Ember` (case-insensitive), resolving symlinks to the underlying `/dev/ttyUSB*` or `/dev/ttyACM*`. Falls back to filtering `jssc`'s port list to those two patterns if `by-id` is empty. | Strong — vendor strings come from the USB device descriptor. |
| **macOS** | Filters `jssc`'s port list to `/dev/cu.usbserial-*`, `/dev/cu.usbmodem*`, `/dev/tty.usbserial-*`, `/dev/tty.usbmodem*`; prefers `cu.*` and drops the `tty.*` twin when both are present. | Decent — identifies "a USB-serial chip" but not specifically an Ember dongle. |
| **Windows** | Returns `jssc`'s `COM*` list verbatim — Windows does not expose enough metadata through plain Java to filter further without WMI / JNA. | Weak. |

If your application wants to drive its own selection UI rather than fail on ambiguity, use the lower-level helper which never throws:

```java
List<SerialPortDiscovery.Candidate> candidates =
        SerialPortDiscovery.listCandidates();
// each Candidate has path(), description() (e.g. the by-id symlink it came
// from), and source() (LINUX_BY_ID / LINUX_TTY / MAC_USB_SERIAL / WINDOWS_COM)
```

Manual lookup hints when something goes wrong:

```bash
ls -l /dev/serial/by-id/        # Linux — stable per-dongle symlinks
ls /dev/cu.usbserial-*          # macOS
```

## Wire format

µBabel traffic rides on a vendor-specific ZCL cluster, with both data and heartbeat surfaced as writable attributes. End devices push data by issuing `WriteAttributesCommand` on this cluster; the coordinator intercepts them via a global command listener and parses the raw octet string.

| Field | Value |
|---|---|
| HA profile ID | `0x0104` |
| Coordinator endpoint | `1` |
| End device endpoint | `10` |
| µBabel cluster ID | `0xFF00` |
| Data attribute (OCTET_STRING) | `0x0003` — carries `ubabel_zb_packet_t` |
| Heartbeat attribute (UINT16) | `0x0004` — monotonic counter |

`ubabel_zb_packet_t` is little-endian and has no length prefix (the ByteArray wrapper strips it):

```c
typedef struct __attribute__((packed)) {
    uint16_t id;            // little-endian
    uint16_t val;           // little-endian
    uint8_t  payload_len;
    uint8_t  payload[];     // UTF-8 string, payload_len bytes
} ubabel_zb_packet_t;
```

These constants must stay in sync with `zigbee.h` on the ESP / Pico side.

---

## Usage

Add to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>paradigmshift-repository</id>
        <name>ParadigmShift Repository</name>
        <url>https://maven.paradigmshift.pt/releases</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>pt.paradigmshift.iot</groupId>
        <artifactId>babel-zigbee</artifactId>
        <version>0.2.0</version>
    </dependency>
</dependencies>
```

### Minimal example

```java
ZigBeeConfig cfg = new ZigBeeConfig.Builder()
        .serialPort("/dev/ttyUSB0")
        .channel(13)
        .panId(0xE5F2)
        .extendedPanId("1122334455667788")
        // .networkKey(...) .tcLinkKey(...)  // override defaults in production
        .build();

ZigBeeCoordinator coord = new ZigBeeCoordinator(cfg);
coord.setPacketHandler((ieee, packet) -> {
    // runs on the zsmartsystems command-listener thread; hand off to your own
    // queue/loop if your handler is not thread-safe
    System.out.printf("packet from %s: id=%d val=%d payload='%s'%n", ieee,
            packet.getId(), packet.getVal(), packet.getPayloadAsString());
});
coord.setHeartbeatHandler((ieee, counter) ->
        System.out.printf("heartbeat from %s: %d%n", ieee, counter));

coord.init();              // forms the network, registers listeners
coord.permitJoin(254);     // open for joining (tighten in production)
```

Per-device state — last id/val/payload + heartbeat counter — is also kept internally and reachable via `coord.getDeviceState(ieee)` and `coord.getKnownDevices()`. Higher-level integrations, such as a future `babel-zigbee-protocol` Babel protocol, will register their own handlers and queue packets out to a protocol thread.

### Sending packets back to a device

Sends are unicast and use the same vendor cluster + `Data` attribute. The call issues a ZCL Write Attributes against `END_DEVICE_ENDPOINT` (10) on the named node:

```java
ZigBeePacket reply = new ZigBeePacket.Builder()
        .id(42)
        .val(0xABCD)
        .payload("ack")
        .build();

Future<CommandResult> tx = coord.transmit(deviceIeee, reply);
// fire-and-forget; or block on tx.get() to wait for the ACK / timeout
```

The destination must already be in `coord.getKnownDevices()` (i.e. the device has joined and its endpoint has been registered, either by the manual bring-up path or by ZDO discovery). If the node, endpoint, or cluster isn't present, `transmit` throws `IllegalStateException` with a descriptive message.

**Payload size limit.** The ZBDongle-E ZCL transport caps the attribute value at 128 bytes. After 6 bytes of ZCL framing and 1 byte for the OCTET_STRING length prefix that leaves `ZigBeeCoordinator.MAX_PACKET_SIZE_BYTES = 121` bytes for the whole `ubabel_zb_packet_t`, i.e. `ZigBeeCoordinator.MAX_PAYLOAD_SIZE_BYTES = 116` bytes for the actual payload after the 5-byte header. `transmit(...)` throws `IllegalArgumentException` if you exceed this — same constraint the ESP firmware enforces on its outgoing path (`ubabel_zb_proto.h`).

### NWK-layer broadcast

Two overloads complement the unicast `transmit`:

```java
coord.transmit(packet);                                                // ALL_DEVICES (0xFFFF)
coord.transmit(ZigBeeBroadcastDestination.BROADCAST_RX_ON, packet);    // rx-on-when-idle only
```

Internally the broadcast path constructs a `WriteAttributesCommand` against the µBabel cluster + `Data` attribute exactly like the unicast path, but sets the destination to a NWK broadcast address and dispatches through `ZigBeeNetworkManager.sendCommand(...)` (no transaction — broadcasts are unacknowledged). Caveats:

- Sleepy end devices that are not currently awake will miss the frame; pick `BROADCAST_RX_ON` deliberately if that matters.
- The return is `boolean`, not `Future<CommandResult>` — it reflects only whether the NCP accepted the command for transmission.
- Joined nodes whose endpoint descriptor does not expose the µBabel cluster simply ignore the frame.

Same `MAX_PACKET_SIZE_BYTES` / `MAX_PAYLOAD_SIZE_BYTES` limits apply.

### Configuration knobs

| Builder method | Default | When to override |
|---|---|---|
| `serialPort(path)` | — (required) | always — pick the right device node for your OS / dongle |
| `serialBaud(baud)` | 115200 | non-default Ember firmware builds |
| `channel(ch)` | 13 | site survey shows interference on 13 |
| `panId(int)` / `extendedPanId(16-char hex)` | 0xE5F2 / `1122334455667788` | running multiple ZigBee networks side-by-side |
| `networkKey(key)` | hard-coded dev default | **production** — but the value only needs to be unique per network; the coordinator distributes it to joining devices encrypted with the TC link key, so it does *not* need to match anything on the ESP firmware |
| `tcLinkKey(key)` | hard-coded dev default | **production** — this one *must* match the bytes compiled into the ESP firmware (`ubabel_zb_proto.c`); both sides preshare it and the coordinator uses it to wrap the network-key delivery during the secured-join handshake |
| `endDeviceId(0xFFFF mask)` | `DEFAULT_END_DEVICE_ID` (0xFFF2) | your end-device firmware advertises a different HA device id |
| `useManualBringup(bool)` | `true` | the historical µBabel ESP firmware bring-up path (default). The firmware does register a real endpoint via `esp_zb_device_register()`, so setting this to `false` and letting zsmartsystems auto-populate via ZDO is expected to work — worth trying once you can test against real hardware |
| `allianceWellKnownKey(key)` | `KEY_ALLIANCE09` | pass `null` to skip `addTransientLinkKey` on NCP firmware that returns `LIBRARY_NOT_PRESENT` |

> **Hardware note:** the library compiles anywhere but requires an Ember EZSP USB dongle (Silicon Labs EM35x / EFR32) to do anything at runtime.

---

## Building

Requires Java 17 and Maven 3.6+.

```bash
mvn verify    # compile (no tests yet)
mvn package   # produces JAR, sources JAR, and Javadoc JAR
mvn install   # also install to ~/.m2/
mvn deploy    # publish to maven.paradigmshift.pt (requires REPOSILITE_TOKEN)
```

### Smoke test

`Main.java` is a smoke-test entry point — it opens the dongle, forms the network, opens it for joining, prints every µBabel packet and heartbeat received, and by default every 5 seconds picks the first joined device and sends it a `"hello N"` packet to exercise the coordinator-to-device transmit path. To run it:

```bash
mvn exec:java -Dexec.mainClass=Main
```

Alternatively, the `executable` Maven profile produces a self-contained fat JAR with `Main` wired as the entry point in the manifest. This jar is intended **only for local testing** — the profile disables `install`/`deploy`, so it is never published to the ParadigmShift Maven repository:

```bash
mvn clean package -P executable
java -jar target/babel-zigbee-0.0.1-executable.jar
```

Three CLI flags are recognised (any order):

| Flag | Effect |
|---|---|
| `--rx-only` (also `rx-only` / `no-tx`) | Disable transmission and use the program as a pure receiver — handy when isolating whether observed packets are real over-the-air traffic from the end device or are echoes of this coordinator's own TX cycle. |
| `--serial-port <path>` | Dongle device node. **Omit to auto-discover** via `ZigBeeCoordinator.autoDiscoverSerialPort()` (works best on Linux; see *Serial device* above). On a Pi the dongle typically enumerates as `/dev/ttyUSB0` or `/dev/ttyACM0`. |
| `--dest-addr <ieee>` | Unicast every TX packet to the given IEEE address (16 hex chars, colons optional — e.g. `00:11:22:33:44:55:66:77` or `0011223344556677`) instead of the first joined device. Useful when several end devices have joined and you want to target one specifically. Ignored when `--rx-only` is set. |

Examples:

```bash
# Auto-discover the dongle (succeeds when exactly one Ember-style port is present)
java -jar target/babel-zigbee-0.0.1-executable.jar

# Explicit path — e.g. when multiple USB-serial devices are plugged in
java -jar target/babel-zigbee-0.0.1-executable.jar --serial-port /dev/ttyUSB0

# Pure receiver
java -jar target/babel-zigbee-0.0.1-executable.jar --rx-only

# Pin the TX target instead of "first joined device"
java -jar target/babel-zigbee-0.0.1-executable.jar \
    --dest-addr 00:11:22:33:44:55:66:77
```

> **Note:** unlike LoRa, there is no `--own-addr` flag — the coordinator's IEEE address is taken from the EZSP dongle's MAC and is not software-configurable from this layer.

The accompanying `Makefile` is a shortcut for the same flow:

```bash
make build    # mvn clean package -P executable
make run      # java -jar target/babel-zigbee-0.0.1-executable.jar
make          # build + run
```

`make run` passes no arguments — supply flags directly to `java -jar …` when you need them, or extend the Makefile target locally.

## Releasing

Push a version tag — the GitHub Actions CI workflow builds and deploys automatically (mirroring the other ParadigmShift Maven libs):

```bash
git tag v0.0.1
git push origin v0.0.1
```

---

## License

Copyright (c) 2026 ParadigmShift, Lda. See [LICENSE](LICENSE) for full terms.

This artifact is developed and maintained exclusively by ParadigmShift, Lda.

Commercial use outside of ParadigmShift requires a written licence.
Contact: [info@paradigmshift.pt](mailto:info@paradigmshift.pt)
