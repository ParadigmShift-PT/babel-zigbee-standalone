import zigbee.ZigBeeCoordinator;
import zigbee.ZigBeeCoordinator.ZigBeeConfig;

public class Main {

    private static final String SERIAL_PORT = "/dev/tty.usbserial-2130";

    public static void main(String[] args) throws Exception {
        ZigBeeConfig cfg = new ZigBeeConfig.Builder()
                .serialPort(SERIAL_PORT)
                .build();

        ZigBeeCoordinator coordinator = new ZigBeeCoordinator(cfg);
        coordinator.setPacketHandler((ieee, packet) -> System.out.printf(
                "Packet from %s: id=%d val=%d payload='%s'%n", ieee,
                packet.getId(), packet.getVal(),
                packet.getPayloadAsString()));
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

        Thread.currentThread().join();
    }
}
