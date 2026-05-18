package zigbee;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import jssc.SerialPortList;

/**
 * Best-effort cross-platform auto-discovery of an Ember EZSP USB dongle.
 *
 * <p>The driver itself has no hardcoded device path — this helper exists for
 * callers that want to skip the explicit
 * {@link ZigBeeCoordinator.ZigBeeConfig.Builder#serialPort(String)} step when
 * exactly one Ember dongle is plugged in. It is intentionally conservative:
 * it never picks a port when the result is ambiguous.
 *
 * <h3>Algorithm</h3>
 * <ul>
 *   <li><b>Linux:</b> walks {@code /dev/serial/by-id/} and keeps entries whose
 *   filename matches known Ember-bearing vendor strings (Silicon Labs CP210x,
 *   Sonoff / ITead ZBDongle-E, EFR32, etc.). Each match is resolved through
 *   its symlink to the real {@code /dev/ttyUSB*} or {@code /dev/ttyACM*}
 *   path. Falls back to the raw {@code jssc} port list filtered to those two
 *   patterns if {@code by-id} is empty or unreadable.</li>
 *   <li><b>macOS:</b> filters {@code jssc.SerialPortList.getPortNames()} to
 *   {@code /dev/cu.usbserial-*}, {@code /dev/cu.usbmodem*},
 *   {@code /dev/tty.usbserial-*}, and {@code /dev/tty.usbmodem*}. macOS does
 *   not expose the USB vendor string through the device-node name, so this
 *   is a weaker filter than the Linux path — it identifies "a USB-serial
 *   chip" but cannot single out an Ember dongle from, say, an ESP32 board
 *   sharing the same CP210x bridge IC.</li>
 *   <li><b>Windows:</b> returns the {@code jssc} {@code COM*} list verbatim.
 *   Windows does not expose enough metadata through plain Java to filter
 *   further without WMI / JNA.</li>
 * </ul>
 *
 * <h3>Safety</h3>
 * {@link #autoDiscover()} returns a path only when exactly one candidate is
 * found. Zero or multiple candidates both throw {@link IllegalStateException}
 * with a message listing what was seen — it never picks blindly, because
 * writing ZigBee NCP configuration into an unrelated USB-serial device would
 * be a non-trivial recovery.
 */
public final class SerialPortDiscovery {

    private SerialPortDiscovery() {}

    /** Why a {@link Candidate} ended up in the result list. */
    public enum Source {
        /** Resolved from a {@code /dev/serial/by-id/} symlink whose name
         *  matched a known Ember vendor string. Highest confidence. */
        LINUX_BY_ID,
        /** Plain {@code /dev/ttyUSB*} or {@code /dev/ttyACM*} entry from
         *  {@code jssc} on Linux, used only when {@code by-id} was empty. */
        LINUX_TTY,
        /** {@code /dev/cu.usbserial-*} or similar from {@code jssc} on macOS. */
        MAC_USB_SERIAL,
        /** Verbatim {@code jssc} entry on Windows. */
        WINDOWS_COM,
        /** Unknown OS — verbatim {@code jssc} entry without filtering. */
        UNKNOWN_OS,
    }

    /**
     * A discovered dongle candidate.
     *
     * @param path         OS device-node path suitable for
     *                     {@link ZigBeeCoordinator.ZigBeeConfig.Builder#serialPort(String)}
     * @param description  human-readable origin (e.g. the {@code by-id}
     *                     symlink that resolved to this path); equals
     *                     {@code path} when there is nothing extra to say
     * @param source       which discovery branch produced this candidate
     */
    public record Candidate(String path, String description, Source source) {}

    private static final String OS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    private static final Path LINUX_BY_ID = Path.of("/dev/serial/by-id");

    private static final Pattern EMBER_BY_ID = Pattern.compile(
            "silicon[_-]?labs|silabs|cp210[12x]|sonoff|itead|efr32|ember",
            Pattern.CASE_INSENSITIVE);

    /**
     * Returns all plausible Ember dongle candidates on this host, in
     * preference order (most-confident first). Never throws — an empty list
     * means nothing matched.
     */
    public static List<Candidate> listCandidates() {
        if (isLinux()) return linuxCandidates();
        if (isMac()) return macCandidates();
        if (isWindows()) return windowsCandidates();
        List<Candidate> fallback = new ArrayList<>();
        for (String p : SerialPortList.getPortNames()) {
            fallback.add(new Candidate(p, p, Source.UNKNOWN_OS));
        }
        return fallback;
    }

    /**
     * Convenience for the common "just find the dongle" case.
     *
     * @return the single discovered serial-port path
     * @throws IllegalStateException if zero or multiple candidates were found;
     *         the message lists everything seen so the caller can either ask
     *         the user to pick or surface it in their own UI.
     */
    public static String autoDiscover() {
        List<Candidate> candidates = listCandidates();
        if (candidates.size() == 1) return candidates.get(0).path();
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "Could not auto-discover an Ember/EZSP dongle on this system ("
                            + OS + "). Set the serial port explicitly, e.g.: "
                            + exampleDevice()
                            + ". All ports seen by jssc: ["
                            + String.join(", ", SerialPortList.getPortNames())
                            + "]");
        }
        StringBuilder msg = new StringBuilder();
        msg.append("Found ").append(candidates.size())
                .append(" candidate dongles — please pick one explicitly:\n");
        for (Candidate c : candidates) {
            msg.append("    ").append(c.path());
            if (!c.path().equals(c.description())) {
                msg.append("   (via ").append(c.description()).append(")");
            }
            msg.append("\n");
        }
        throw new IllegalStateException(msg.toString().stripTrailing());
    }

    private static List<Candidate> linuxCandidates() {
        List<Candidate> out = new ArrayList<>();
        if (Files.isDirectory(LINUX_BY_ID)) {
            try (DirectoryStream<Path> entries =
                         Files.newDirectoryStream(LINUX_BY_ID)) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    if (!EMBER_BY_ID.matcher(name).find()) continue;
                    try {
                        Path real = entry.toRealPath();
                        out.add(new Candidate(real.toString(), entry.toString(),
                                              Source.LINUX_BY_ID));
                    } catch (IOException ignored) {
                        // Dangling symlink — skip silently.
                    }
                }
            } catch (IOException ignored) {
                // Fall through to the jssc fallback below.
            }
        }
        if (!out.isEmpty()) return dedupByPath(out);

        for (String p : SerialPortList.getPortNames()) {
            if (p.startsWith("/dev/ttyUSB") || p.startsWith("/dev/ttyACM")) {
                out.add(new Candidate(p, p, Source.LINUX_TTY));
            }
        }
        return out;
    }

    private static List<Candidate> macCandidates() {
        List<Candidate> out = new ArrayList<>();
        for (String p : SerialPortList.getPortNames()) {
            if (p.startsWith("/dev/cu.usbserial-")
                    || p.startsWith("/dev/cu.usbmodem")
                    || p.startsWith("/dev/tty.usbserial-")
                    || p.startsWith("/dev/tty.usbmodem")) {
                out.add(new Candidate(p, p, Source.MAC_USB_SERIAL));
            }
        }
        return dedupMacTwins(out);
    }

    private static List<Candidate> windowsCandidates() {
        List<Candidate> out = new ArrayList<>();
        for (String p : SerialPortList.getPortNames()) {
            out.add(new Candidate(p, p, Source.WINDOWS_COM));
        }
        return out;
    }

    private static List<Candidate> dedupByPath(List<Candidate> in) {
        Set<String> seen = new LinkedHashSet<>();
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : in) {
            if (seen.add(c.path())) out.add(c);
        }
        return out;
    }

    /** macOS exposes both {@code /dev/cu.*} and {@code /dev/tty.*} for the
     *  same device. Prefer the {@code cu.*} side (the convention for
     *  outgoing connections) and suppress the {@code tty.*} twin when both
     *  are present. */
    private static List<Candidate> dedupMacTwins(List<Candidate> in) {
        Set<String> cuSuffixes = new HashSet<>();
        for (Candidate c : in) {
            if (c.path().startsWith("/dev/cu.")) {
                cuSuffixes.add(c.path().substring("/dev/cu.".length()));
            }
        }
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : in) {
            if (c.path().startsWith("/dev/tty.")) {
                String suffix = c.path().substring("/dev/tty.".length());
                if (cuSuffixes.contains(suffix)) continue;
            }
            out.add(c);
        }
        return out;
    }

    private static boolean isLinux() { return OS.contains("linux"); }
    private static boolean isMac() {
        return OS.contains("mac") || OS.contains("darwin");
    }
    private static boolean isWindows() { return OS.contains("windows"); }

    private static String exampleDevice() {
        if (isLinux()) return "/dev/ttyUSB0";
        if (isMac()) return "/dev/cu.usbserial-XXXX";
        if (isWindows()) return "COM3";
        return "/dev/ttyUSB0";
    }
}
