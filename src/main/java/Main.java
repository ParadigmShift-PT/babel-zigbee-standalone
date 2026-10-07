import com.zsmartsystems.zigbee.IeeeAddress;
import com.zsmartsystems.zigbee.app.otaserver.ZigBeeOtaFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import zigbee.ZigBeeCoordinator;
import zigbee.ZigBeeCoordinator.ZigBeeConfig;
import zigbee.ZigBeePacket;

public class Main {

    private static final long TX_PERIOD_MS = 5_000;
    private static final long OTA_NOTIFY_PERIOD_S = 60;

    public static void main(String[] args) throws Exception {
        // By default the smoke test exercises both sides of the radio: it
        // joins the first device that comes up and sends a "hello N" packet
        // to it every 5 s while the receive path prints every µBabel packet
        // and heartbeat received.
        //
        // Flags (any order):
        //   --rx-only | rx-only | no-tx   skip transmission (pure receiver)
        //   --serial-port <path>           dongle device node. Omit to let
        //                                  ZigBeeCoordinator.autoDiscoverSerialPort()
        //                                  find it (works best on Linux; on
        //                                  macOS / Windows you may need to
        //                                  pass it explicitly when more than
        //                                  one USB-serial device is plugged
        //                                  in).
        //   --dest-addr <ieee>             unicast to the given IEEE address
        //                                  (16 hex chars, colons optional)
        //                                  instead of the first joined device.
        //                                  Ignored when --rx-only is set.
        Args parsed = Args.parse(args);
        boolean transmit = parsed.transmit;
        IeeeAddress destAddr = parsed.destAddr; // null = first joined device

        String serialPort = parsed.serialPort;
        if (serialPort == null) {
            try {
                serialPort = ZigBeeCoordinator.autoDiscoverSerialPort();
                System.out.println("Serial port: " + serialPort
                                   + "  (auto-discovered)");
            } catch (IllegalStateException e) {
                System.err.println(e.getMessage());
                System.err.println(
                        "Re-run with --serial-port <path> to override.");
                System.exit(1);
                return;
            }
        } else {
            System.out.println("Serial port: " + serialPort);
        }
        if (transmit) {
            System.out.println(destAddr == null
                    ? String.format(
                            "Mode: TX+RX  (unicasting to first joined device every %d s)",
                            TX_PERIOD_MS / 1000)
                    : String.format(
                            "Mode: TX+RX  (unicasting to %s every %d s)",
                            destAddr, TX_PERIOD_MS / 1000));
        } else {
            System.out.println("Mode: RX-only  (no transmissions)");
            if (destAddr != null) {
                System.out.println(
                        "Note: --dest-addr is ignored in rx-only mode.");
            }
        }

        List<ZigBeeOtaFile> otaImages =
                parsed.otaPath == null ? null : loadOtaImages(parsed.otaPath);

        ZigBeeConfig cfg = new ZigBeeConfig.Builder()
                .serialPort(serialPort)
                .build();

        ZigBeeCoordinator coordinator = new ZigBeeCoordinator(cfg);
        coordinator.setPacketHandler((ieee, packet) -> System.out.printf(
                "Packet from %s: %d bytes [%s]%n", ieee,
                packet.getPayload().length, packet));
        coordinator.setHeartbeatHandler((ieee, counter) -> System.out.printf(
                "Heartbeat from %s: counter=%d%n", ieee, counter));

        coordinator.init();
        System.out.println("Coordinator IEEE: " +
                           coordinator.getCoordinatorIeee());

        coordinator.permitJoin(254);
        System.out.println(
                "Network open for joining. Listening for packets...");

        Runtime.getRuntime().addShutdownHook(
                new Thread(coordinator::stop, "zigbee-shutdown"));

        if (otaImages != null) {
            coordinator.setOtaImages(otaImages);
            ScheduledExecutorService otaNotify =
                    Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "zigbee-ota-notify");
                        t.setDaemon(true);
                        return t;
                    });
            otaNotify.scheduleAtFixedRate(() -> {
                try {
                    coordinator.notifyOtaAll();
                } catch (Exception e) {
                    System.err.println("OTA notify failed: " + e);
                }
            }, OTA_NOTIFY_PERIOD_S, OTA_NOTIFY_PERIOD_S, TimeUnit.SECONDS);
            System.out.printf("OTA - serving images=%d notify_every=%ds%n",
                              otaImages.size(), OTA_NOTIFY_PERIOD_S);
        }

        if (transmit) {
            Thread txDemo = new Thread(
                    () -> runTxDemo(coordinator, destAddr),
                    "zigbee-tx-demo");
            txDemo.setDaemon(true);
            txDemo.start();
        }

        Thread.currentThread().join();
    }

    private static List<ZigBeeOtaFile> loadOtaImages(Path path)
            throws IOException {
        List<Path> files;
        if (Files.isDirectory(path)) {
            try (Stream<Path> listing = Files.list(path)) {
                files = listing
                        .filter(f -> f.getFileName().toString().endsWith(".zigbee"))
                        .sorted()
                        .toList();
            }
        } else {
            files = List.of(path);
        }

        List<ZigBeeOtaFile> images = new ArrayList<>();
        for (Path file : files) {
            ZigBeeOtaFile image = new ZigBeeOtaFile(Files.readAllBytes(file));
            System.out.printf("OTA - image file=%s manufacturer=0x%04X "
                              + "type=0x%04X version=%08X size=%d%n",
                              file.getFileName(), image.getManufacturerCode(),
                              image.getImageType(), image.getFileVersion(),
                              image.getImageSize());
            images.add(image);
        }
        if (images.isEmpty()) {
            throw new IllegalArgumentException(
                    "--ota found no .zigbee images in " + path);
        }
        return images;
    }

    private static void runTxDemo(ZigBeeCoordinator coordinator,
                                  IeeeAddress fixedTarget) {
        int seq = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(TX_PERIOD_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }

            IeeeAddress target;
            if (fixedTarget != null) {
                target = fixedTarget;
            } else {
                Iterator<IeeeAddress> peers =
                        coordinator.getKnownDevices().iterator();
                if (!peers.hasNext()) {
                    continue;
                }
                target = peers.next();
            }
            seq++;

            ZigBeePacket pkt = new ZigBeePacket.Builder()
                    .payload("hello " + seq)
                    .build();
            try {
                coordinator.transmit(target, pkt);
                System.out.printf("TX → %s: seq=%d payload='%s'%n", target,
                                  seq, pkt.getPayloadAsString());
            } catch (Exception e) {
                System.err.println("TX failed: " + e.getMessage());
            }
        }
    }

    private static final class Args {
        final boolean transmit;
        final String serialPort;
        final IeeeAddress destAddr;
        final Path otaPath;

        private Args(boolean transmit, String serialPort,
                     IeeeAddress destAddr, Path otaPath) {
            this.transmit = transmit;
            this.serialPort = serialPort;
            this.destAddr = destAddr;
            this.otaPath = otaPath;
        }

        static Args parse(String[] args) {
            boolean transmit = true;
            String serialPort = null;
            IeeeAddress destAddr = null;
            Path otaPath = null;
            for (int i = 0; i < args.length; i++) {
                String a = args[i].toLowerCase();
                switch (a) {
                    case "rx-only":
                    case "--rx-only":
                    case "no-tx":
                        transmit = false;
                        break;
                    case "--serial-port":
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException(
                                    "--serial-port requires a path argument");
                        }
                        serialPort = args[++i];
                        break;
                    case "--dest-addr":
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException(
                                    "--dest-addr requires an IEEE address argument");
                        }
                        destAddr = parseIeeeAddr(args[++i]);
                        break;
                    case "--ota":
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException(
                                    "--ota requires a .zigbee file or a directory");
                        }
                        otaPath = Path.of(args[++i]);
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "Unknown argument: " + args[i]);
                }
            }
            return new Args(transmit, serialPort, destAddr, otaPath);
        }

        private static IeeeAddress parseIeeeAddr(String s) {
            // IeeeAddress(String) wants 16 contiguous hex chars. Tolerate the
            // colon-separated form that toString() emits.
            String hex = s.replace(":", "").replace("-", "");
            if (hex.length() != 16) {
                throw new IllegalArgumentException(
                        "Invalid --dest-addr value (expected 16 hex chars or"
                                + " colon-separated IEEE, e.g."
                                + " 00:11:22:33:44:55:66:77): " + s);
            }
            try {
                return new IeeeAddress(hex);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Invalid --dest-addr value: " + s + " (" + e.getMessage()
                                + ")");
            }
        }
    }
}
