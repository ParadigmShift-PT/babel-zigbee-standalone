# Babel ZigBee

A Java ZigBee coordinator for Raspberry Pi gateways, built on the [zsmartsystems ZigBee stack](https://github.com/zsmartsystems/com.zsmartsystems.zigbee) with an Ember (EZSP) USB dongle as the radio backend. It forms a ZigBee Home Automation network, registers a vendor-specific cluster, and exchanges µBabel packets with ESP32 / Raspberry Pi Pico end devices via ZCL Write Attribute commands.

The artifact is intended to back a future Babel protocol providing ZigBee connectivity to swarm-style applications on Raspberry Pi gateways. Today it is a self-contained coordinator — there is no Babel dependency yet.

**Group ID:** `pt.paradigmshift.iot`
**Artifact ID:** `babel-zigbee`
**Current version:** `0.0.1`
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

The serial port is passed in via `ZigBeeConfig.Builder.serialPort(...)`. The smoke-test `Main.java` defaults to `/dev/tty.usbserial-2130` (macOS naming); on a Raspberry Pi the dongle typically enumerates as `/dev/ttyUSB0` or `/dev/ttyACM0`. Edit the `SERIAL_PORT` constant in `Main.java`, or supply your own value when constructing the config from an application.

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
        <version>0.0.1</version>
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

### Configuration knobs

| Builder method | Default | When to override |
|---|---|---|
| `serialPort(path)` | — (required) | always — pick the right device node for your OS / dongle |
| `serialBaud(baud)` | 115200 | non-default Ember firmware builds |
| `channel(ch)` | 13 | site survey shows interference on 13 |
| `panId(int)` / `extendedPanId(16-char hex)` | 0xE5F2 / `1122334455667788` | running multiple ZigBee networks side-by-side |
| `networkKey(key)` / `tcLinkKey(key)` | hard-coded dev defaults | **production** — both keys must match what the end-device firmware carries in `zigbee.h` |
| `endDeviceId(0xFFFF mask)` | `DEFAULT_END_DEVICE_ID` (0xFFF2) | your end-device firmware advertises a different HA device id |
| `useManualBringup(bool)` | `true` | your end-device firmware responds to ZDO Active Endpoints / Simple Descriptor requests — in that case set `false` and let zsmartsystems auto-populate the node, endpoints and clusters |
| `allianceWellKnownKey(key)` | `KEY_ALLIANCE09` | pass `null` to skip `addTransientLinkKey` on NCP firmware that returns `LIBRARY_NOT_PRESENT` |

> **Hardware note:** the library compiles anywhere but requires an Ember EZSP USB dongle (Silicon Labs EM35x / EFR32) to do anything at runtime.

---

## Building

Requires Java 21 and Maven 3.6+.

```bash
mvn verify    # compile (no tests yet)
mvn package   # produces JAR, sources JAR, and Javadoc JAR
mvn install   # also install to ~/.m2/
mvn deploy    # publish to maven.paradigmshift.pt (requires REPOSILITE_TOKEN)
```

### Smoke test

`Main.java` is a smoke-test entry point — it forms the network, opens it for joining, and prints every µBabel packet and heartbeat received. To run it:

```bash
mvn exec:java -Dexec.mainClass=Main
```

Alternatively, the `executable` Maven profile produces a self-contained fat JAR with `Main` wired as the entry point in the manifest. This jar is intended **only for local testing** — the profile disables `install`/`deploy`, so it is never published to the ParadigmShift Maven repository:

```bash
mvn clean package -P executable
java -jar target/babel-zigbee-0.0.1-executable.jar
```

The accompanying `Makefile` is a shortcut for the same flow:

```bash
make build    # mvn clean package -P executable
make run      # java -jar target/babel-zigbee-0.0.1-executable.jar
make          # build + run
```

## Releasing

Push a version tag — the GitHub Actions CI workflow builds and deploys automatically (mirroring the other ParadigmShift Maven libs):

```bash
git tag v0.0.1
git push origin v0.0.1
```

---

## License

Copyright (c) 2026 ParadigmShift, Lda. See [LICENSE](LICENSE) for full terms.

This artifact is developed and maintained exclusively by ParadigmShift, Lda. It has no relationship to NOVA FCT or the TaRDIS European research project.

Commercial use outside of ParadigmShift requires a written licence.
Contact: [info@paradigmshift.pt](mailto:info@paradigmshift.pt)
