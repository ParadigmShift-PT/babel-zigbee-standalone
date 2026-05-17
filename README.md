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
| Extended PAN ID | `0x0011223344556677_88` (default) |
| Profile | ZigBee Home Automation (`0x0104`) |
| Trust centre policy | `TC_JOIN_INSECURE` (open joins; tighten for production) |

The coordinator opens the network for joining via `permitJoin(254)`, formed on a fixed channel/PAN with hard-coded network and trust-centre link keys.

### Serial device

The default serial port is hard-coded in `Main.java` as `/dev/tty.usbserial-2130` (macOS naming). On a Raspberry Pi the dongle typically enumerates as `/dev/ttyUSB0` or `/dev/ttyACM0`. Edit the `SERIAL_PORT` constant in `Main.java` (or, once the API is extracted into a library class, pass it as a constructor argument) before flashing a new dongle.

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

`Main.java` is currently the only entry point — it brings up a coordinator end-to-end:

```java
ZigBeeSerialPort port = new ZigBeeSerialPort(
        "/dev/ttyUSB0", 115200, FlowControl.FLOWCONTROL_OUT_NONE);

ZigBeeDongleEzsp dongle = new ZigBeeDongleEzsp(port);
dongle.updateDefaultPolicy(EzspPolicyId.EZSP_TRUST_CENTER_POLICY,
                           EzspDecisionId.EZSP_ALLOW_JOINS);

ZigBeeNetworkManager manager = new ZigBeeNetworkManager(dongle);
manager.setSerializer(DefaultSerializer.class, DefaultDeserializer.class);

manager.initialize();
manager.setZigBeeChannel(ZigBeeChannel.CHANNEL_13);
manager.setZigBeePanId(0xE5F2);
manager.setZigBeeExtendedPanId(new ExtendedPanId("001122334455667788"));
manager.addSupportedClientCluster(0xFF00);
manager.addSupportedServerCluster(0xFF00);

manager.startup(true);
manager.permitJoin(254);
```

Incoming µBabel packets are surfaced through `addCommandListener(...)`, filtered down to `WriteAttributesCommand` instances on cluster `0xFF00`. Higher-level integrations — such as a future `babel-zigbee-protocol` Babel protocol — will register their own handler and queue packets out to the protocol thread.

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
