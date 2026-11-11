import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;

import dev.hivens.libtray.Tray;
import dev.hivens.libtray.TrayBuilder;
import dev.hivens.libtray.TrayEvent;
import dev.hivens.libtray.TrayMenu;
import dev.hivens.libtray.TrayMenuItem;

/**
 * ClamAV-Statusanzeige fuer Debian 13 und KDE Plasma (Wayland).
 * Benoetigt Java 25 sowie die beigefuegte libtray-Erweiterung fuer PNG-Menueicons.
 * Der original ClamAV-Teufel wird bei Statusaenderungen eingefaerbt.
 * Empfangt Virenfunde vom separaten clamd-VirusEvent-Helferskript.
 * Die Konfiguration bleibt unveraendert; ein manuelles Update weckt FreshClam.
 */
public final class ClamAvTray {

    private static final String APP_VERSION = "1.5.2";
    private static final String CLAMD = "clamav-daemon.service";
    private static final String FRESHCLAM = "clamav-freshclam.service";
    private static final String CLAMONACC = "clamav-clamonacc.service";
    private static final int VIRUS_EVENT_PORT = 47619;
    private static final long DUPLICATE_INTERVAL_MS = 5_000;

    // libtray und die Statusabfrage besitzen nur Hintergrundthreads.
    // Daher muss der Hauptthread bis zum Menüpunkt "Beenden" warten.
    private final CountDownLatch shutdownSignal = new CountDownLatch(1);

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "clamav-statusabfrage");
                thread.setDaemon(true);
                return thread;
            });

    private final ExecutorService eventWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "clamav-virenfund-empfang");
        thread.setDaemon(true);
        return thread;
    });

    private final ExecutorService maintenanceWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "clamav-verwaltung");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService scanWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "clamav-manueller-scan");
        thread.setDaemon(true);
        return thread;
    });
    // Update und Neustart dürfen nicht gleichzeitig ausgeführt werden.
    private final AtomicBoolean maintenanceBusy = new AtomicBoolean(false);
    private final AtomicBoolean scanBusy = new AtomicBoolean(false);
    private final AtomicBoolean scanCancelRequested = new AtomicBoolean(false);

    private Tray tray;
    private DatagramSocket eventSocket;
    private volatile Detection lastDetection;
    private volatile boolean alertUnconfirmed;
    private volatile String updateStatus = "bereit";
    private volatile String restartStatus = "bereit";
    private volatile String scanStatus = "noch kein Scan";
    private volatile boolean scanRunning;
    private volatile Process activeScanProcess;
    // Die KDE-DBus-Menue-IDs duerfen nicht bei jedem 30-Sekunden-Poll
    // grundlos ersetzt werden, sonst koennen offene Menues Klicks verlieren.
    private String currentMenuState;
    private byte[] iconGreen;
    private byte[] iconYellow;
    private byte[] iconRed;
    private byte[] dotGreen;
    private byte[] dotYellow;
    private byte[] dotRed;

    public static void main(String[] args) {
        new ClamAvTray().start();
    }

    private void start() {
        try {
            BufferedImage logo = readLogo();
            iconGreen = makeDevilIcon(logo, 0.35f);
            iconYellow = makeDevilIcon(logo, 0.14f);
            iconRed = makeDevilIcon(logo, 0.01f);
            dotGreen = makeStatusDot(new Color(22, 172, 74));
            dotYellow = makeStatusDot(new Color(221, 161, 16));
            dotRed = makeStatusDot(new Color(215, 54, 54));
        } catch (IOException exception) {
            System.err.println("ClamAV-Logo konnte nicht geladen werden: " + exception.getMessage());
            return;
        }

        tray = Tray.create(TrayBuilder.of("ClamAV Status", iconYellow)
                .tooltip("ClamAV: Status wird geprüft")
                .maxIconSize(128)
                .menu(makeMenu("wird geprüft …", "wird geprüft …", "wird geprüft …",
                        "wird geprüft …", "wird geprüft …", null, false))
                .build());
        if (tray == null) {
            System.err.println("KDE-Tray konnte nicht initialisiert werden. D-Bus-Session prüfen.");
            return;
        }

        tray.onEvent(event -> {
            if (event instanceof TrayEvent.MenuItemSelected selected) {
                if ("refresh".equals(selected.getId())) {
                    scheduler.execute(this::refresh);
                } else if ("update".equals(selected.getId())) {
                    if (maintenanceBusy.compareAndSet(false, true)) {
                        updateStatus = "Autorisierung wird geprüft";
                        scheduler.execute(this::refresh);
                        maintenanceWorker.execute(this::requestSignatureUpdate);
                    }
                } else if ("restart".equals(selected.getId())) {
                    if (maintenanceBusy.compareAndSet(false, true)) {
                        restartStatus = "Autorisierung wird geprüft";
                        scheduler.execute(this::refresh);
                        maintenanceWorker.execute(this::restartAllServices);
                    }
                } else if ("scan-file".equals(selected.getId())) {
                    startManualScan(false);
                } else if ("scan-folder".equals(selected.getId())) {
                    startManualScan(true);
                } else if ("scan-cancel".equals(selected.getId())) {
                    cancelManualScan();
                } else if ("ack".equals(selected.getId())) {
                    if (lastDetection != null) {
                        alertUnconfirmed = false;
                        System.out.println("Virenalarm wurde im Tray bestätigt.");
                    }
                    scheduler.execute(this::refresh);
                } else if ("exit".equals(selected.getId())) {
                    shutdownSignal.countDown();
                }
            }
        });
        startEventListener();
        scheduler.scheduleWithFixedDelay(this::refresh, 0, 30, TimeUnit.SECONDS);

        try {
            shutdownSignal.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            if (eventSocket != null) eventSocket.close();
            eventWorker.shutdownNow();
            maintenanceWorker.shutdownNow();
            scanCancelRequested.set(true);
            Process scanProcess = activeScanProcess;
            if (scanProcess != null) scanProcess.destroyForcibly();
            scanWorker.shutdownNow();
            scheduler.shutdownNow();
            tray.close();
        }
    }

    private TrayMenu makeMenu(String daemon, String freshclam, String onAccess,
                              String signature, String signatureDate,
                              Detection detected, boolean pending) {
        return new TrayMenu(
                new TrayMenuItem.Standard("title", "ClamAV – Status", false),
                TrayMenuItem.Separator.INSTANCE,
                // KDE entsaettigt die gesamte Zeile, wenn enabled=false ist.
                // Darum bleiben diese reinen Statusanzeigen technisch aktiv;
                // bei Auswahl wird absichtlich keine Aktion ausgefuehrt.
                new TrayMenuItem.Standard("daemon", serviceLine("ClamAV-Daemon", daemon),
                        true, serviceIcon(daemon)),
                new TrayMenuItem.Standard("freshclam", serviceLine("FreshClam", freshclam),
                        true, serviceIcon(freshclam)),
                new TrayMenuItem.Standard("onaccess", serviceLine("On-Access", onAccess),
                        true, serviceIcon(onAccess)),
                new TrayMenuItem.Standard("restart-status", "Neustart: " + restartStatus, false),
                new TrayMenuItem.Standard("restart", "Alle Dienste neu starten"),
                TrayMenuItem.Separator.INSTANCE,
                new TrayMenuItem.Standard("signature", "Geladene Signaturen: " + signature, false),
                new TrayMenuItem.Standard("date", "Signaturdatum: " + signatureDate, false),
                new TrayMenuItem.Standard("update-status", "Update: " + updateStatus, false),
                new TrayMenuItem.Standard("update", "Signaturen jetzt aktualisieren"),
                TrayMenuItem.Separator.INSTANCE,
                new TrayMenuItem.Standard("scan-file", "Datei scannen …"),
                new TrayMenuItem.Standard("scan-folder", "Ordner scannen …"),
                // Auch der reine Status bleibt aktiv, damit Plasma das
                // farbige Symbol nicht wie einen deaktivierten Punkt ausgraut.
                new TrayMenuItem.Standard("scan-status", scanMenuLine(),
                        true, scanBusy.get() ? dotYellow : null),
                // Immer anklickbar, da Plasma deaktivierte Menüeinträge
                // gelegentlich auch nach einer Statusänderung grau belässt.
                new TrayMenuItem.Standard("scan-cancel", "Laufenden Scan abbrechen"),
                TrayMenuItem.Separator.INSTANCE,
                new TrayMenuItem.Standard("last-virus", "Letzter Fund: "
                        + (detected == null ? "keiner" : shorten(detected.virusName(), 65)), false),
                new TrayMenuItem.Standard("last-file", "Datei: "
                        + (detected == null ? "–" : shorten(detected.fileName(), 65)), false),
                new TrayMenuItem.Standard("alarm-state", "Alarmstatus: "
                        + (detected == null ? "kein Fund" : pending ? "OFFEN" : "bestätigt"), false),
                // IMMER aktiv, bereits beim ersten Menue vor einem Fund.
                // Plasma kann ein anfänglich deaktiviertes D-Bus-Menueelement
                // trotz spaeterem Layout-Update weiter als grau darstellen.
                // Ohne Fund ist ein Klick ein harmloser No-op.
                new TrayMenuItem.Standard("ack", "Virenalarm bestätigen"),
                TrayMenuItem.Separator.INSTANCE,
                new TrayMenuItem.Standard("refresh", "Status jetzt aktualisieren"),
                TrayMenuItem.Separator.INSTANCE,
                new TrayMenuItem.Standard("app-version", "ClamAV-Tray: Version " + APP_VERSION, false),
                new TrayMenuItem.Standard("exit", "Tray-Anzeige beenden"));
    }

    private static String serviceLine(String name, String status) {
        return name + ": " + status;
    }

    private byte[] serviceIcon(String status) {
        if ("läuft".equals(status)) return dotGreen;
        if (status.startsWith("wird geprüft") || "unbekannt".equals(status)) return dotYellow;
        return dotRed;
    }

    private String scanMenuLine() {
        if (scanRunning) {
            return "SCAN LÄUFT: " + shorten(scanStatus.replaceFirst("^Läuft: ", ""), 56);
        }
        if (scanBusy.get()) return "Scanvorbereitung: " + shorten(scanStatus, 56);
        return "Letzter Scan: " + shorten(scanStatus, 58);
    }

    private void startManualScan(boolean folder) {
        // Nur eine Dateiauswahl bzw. ein Scan gleichzeitig.
        if (!scanBusy.compareAndSet(false, true)) {
            notifyScan("Scan läuft bereits", "Bitte den aktuellen Scan abwarten oder abbrechen.", true);
            return;
        }
        scanCancelRequested.set(false);
        scanStatus = folder ? "Ordnerauswahl läuft …" : "Dateiauswahl läuft …";
        scheduler.execute(this::refresh);
        scanWorker.execute(() -> chooseAndScan(folder));
    }

    private void cancelManualScan() {
        if (!scanBusy.get()) return;
        scanCancelRequested.set(true);
        scanStatus = "Abbruch angefordert …";
        Process process = activeScanProcess;
        if (process != null) process.destroyForcibly();
        scheduler.execute(this::refresh);
    }

    private void chooseAndScan(boolean folder) {
        try {
            // Die Dateiauswahl ist ein natives KDE-Fenster und blockiert
            // ausschließlich diesen Hintergrundthread, nicht das Tray.
            String pickerArgument = folder ? "--getexistingdirectory" : "--getopenfilename";
            Process picker = new ProcessBuilder("kdialog", pickerArgument,
                    System.getProperty("user.home"))
                    // Qt/KDE-Diagnosen auf stderr duerfen nicht im Dateipfad landen.
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            activeScanProcess = picker;
            if (scanCancelRequested.get()) picker.destroyForcibly();
            if (!picker.waitFor(600, TimeUnit.SECONDS)) {
                picker.destroyForcibly();
                scanStatus = "Dateiauswahl: Zeitüberschreitung";
                return;
            }
            String selected = new String(picker.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).replaceFirst("[\\r\\n]+$", "");
            activeScanProcess = null;

            if (scanCancelRequested.get()) {
                scanStatus = "Scan abgebrochen";
                return;
            }
            if (picker.exitValue() != 0 || selected.isEmpty()) {
                scanStatus = "Auswahl abgebrochen";
                return;
            }

            // --getopenfilename liefert normalerweise einen absoluten Pfad.
            // Falls KDE doch eine file://-URL liefert, auch diese akzeptieren.
            Path target = selected.startsWith("file:")
                    ? Path.of(URI.create(selected)) : Path.of(selected);
            System.out.println("KDE-Dateiauswahl: " + target);
            if (!(folder ? Files.isDirectory(target) : Files.isRegularFile(target))) {
                System.err.println("KDE-Dateiauswahl existiert nicht im erwarteten Format: "
                        + target + " (existiert=" + Files.exists(target)
                        + ", Datei=" + Files.isRegularFile(target)
                        + ", Ordner=" + Files.isDirectory(target) + ")");
                scanStatus = "Auswahl ist "
                        + (folder ? "kein gültiger Ordner" : "keine gültige Datei");
                notifyScan("Scan nicht gestartet", scanStatus, true);
                return;
            }

            scanStatus = "Starte Scan: " + shorten(target.toString(), 45);
            scheduler.execute(this::refresh);

            List<String> arguments = new java.util.ArrayList<>(List.of(
                    "clamscan", "--stdout", "--infected"));
            if (folder) {
                arguments.add("--recursive");
                arguments.add("--follow-dir-symlinks=0");
                arguments.add("--follow-file-symlinks=0");
            }
            // Keine Shell: auch Pfade mit Leerzeichen oder Sonderzeichen
            // werden als genau ein Argument an clamscan übergeben.
            arguments.add(target.toAbsolutePath().toString());

            Process scanner = new ProcessBuilder(arguments)
                    .redirectErrorStream(true).start();
            activeScanProcess = scanner;
            scanRunning = true;
            scanStatus = "Läuft: " + shorten(target.toString(), 49);
            scheduler.execute(this::refresh);
            if (scanCancelRequested.get()) scanner.destroyForcibly();
            String infectedPath = null;
            String virusName = null;
            int foundCount = 0;
            String lastOutput = "";

            // Ausgabe während des Scans fortlaufend lesen, damit clamscan
            // nicht an einem vollen Pipe-Puffer hängen bleibt.
            try (BufferedReader output = new BufferedReader(new InputStreamReader(
                    scanner.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = output.readLine()) != null) {
                    System.out.println("[clamscan] " + line);
                    if (!line.isBlank()) lastOutput = line;
                    if (line.endsWith(" FOUND")) {
                        foundCount++;
                        if (infectedPath == null) {
                            int separator = line.lastIndexOf(": ");
                            infectedPath = separator < 0 ? target.toString()
                                    : line.substring(0, separator);
                            virusName = separator < 0 ? "Unbekannte Signatur"
                                    : line.substring(separator + 2, line.length() - " FOUND".length());
                        }
                    }
                }
            }

            int code = scanner.waitFor();
            if (scanCancelRequested.get()) {
                scanStatus = "Scan abgebrochen";
                return;
            }
            if (foundCount > 0) {
                scanStatus = foundCount + " Fund(e) in " + shorten(target.toString(), 40);
                recordDetection(cleanText(infectedPath), cleanText(virusName));
                System.err.println("Manueller Scan: insgesamt " + foundCount + " Funde.");
            } else if (code == 0) {
                scanStatus = "Kein Fund: " + shorten(target.toString(), 48);
                notifyScan("Scan abgeschlossen", "Keine Bedrohung gefunden: " + target, false);
            } else {
                scanStatus = "Scan fehlgeschlagen (Code " + code + ")";
                notifyScan("Scan fehlgeschlagen", "Datei/Ordner: " + target
                        + "\nDetails im Terminal: " + shorten(lastOutput, 120), true);
            }
            if (foundCount > 0 && code > 1) {
                scanStatus = foundCount + " Fund(e), zusätzlich Scanfehler";
                notifyScan("Scan unvollständig",
                        "Es gab zusätzlich zu den Funden Lesefehler. Details im Terminal.", true);
            }
        } catch (IOException exception) {
            scanStatus = "kdialog oder clamscan konnte nicht gestartet werden";
            System.err.println("Manueller ClamAV-Scan: " + exception.getMessage());
            if (!scanCancelRequested.get()) {
                notifyScan("Scan nicht möglich", "Bitte kdialog und clamscan prüfen: "
                        + exception.getMessage(), true);
            }
        } catch (IllegalArgumentException exception) {
            scanStatus = "Ungültiger Pfad aus der Dateiauswahl";
            System.err.println("KDE-Dateiauswahl: " + exception.getMessage());
            if (!scanCancelRequested.get()) {
                notifyScan("Ungültige Dateiauswahl", "Das ausgewählte Ziel konnte nicht gelesen werden.", true);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            scanStatus = "Scan unterbrochen";
        } finally {
            activeScanProcess = null;
            scanRunning = false;
            scanBusy.set(false);
            if (!scheduler.isShutdown()) scheduler.execute(this::refresh);
        }
    }

    private static void notifyScan(String title, String message, boolean error) {
        CommandResult result = runCommand(List.of("notify-send", "--app-name=ClamAV Tray",
                error ? "--urgency=critical" : "--urgency=normal",
                "--icon=" + (error ? "dialog-error" : "dialog-information"),
                "ClamAV: " + title, message));
        if (result.exitCode() != 0) {
            System.err.println("Scan-Benachrichtigung fehlgeschlagen: " + result.output());
        }
    }

    private void restartAllServices() {
        try {
            // Das Kommando enthält ausschließlich fest definierte systemd-Units.
            // pkexec fordert bei Bedarf das Administratorpasswort an.
            CommandResult result = runCommand(List.of(
                    "pkexec", "/usr/bin/systemctl", "restart",
                    CLAMD, FRESHCLAM, CLAMONACC), 180);

            if (result.exitCode() == 0) {
                restartStatus = "Neustartauftrag abgeschlossen";
                System.out.println("Neustart der drei ClamAV-Dienste abgeschlossen.");
                notifyUpdate("Dienste neu gestartet",
                        "Der Neustartauftrag ist abgeschlossen."
                                + " Der aktuelle Dienststatus wird neu geprüft.", false);
            } else {
                restartStatus = "Fehler beim Neustart";
                System.err.println("ClamAV-Dienstneustart fehlgeschlagen ("
                        + result.exitCode() + "): " + result.output());
                notifyUpdate("Neustart fehlgeschlagen",
                        "Mindestens ein Dienst konnte nicht neu gestartet werden."
                                + " Details stehen im Startterminal.", true);
            }
            if (!scheduler.isShutdown()) {
                scheduler.schedule(this::refresh, 5, TimeUnit.SECONDS);
                scheduler.schedule(this::refresh, 20, TimeUnit.SECONDS);
            }
        } finally {
            maintenanceBusy.set(false);
            if (!scheduler.isShutdown()) scheduler.execute(this::refresh);
        }
    }

    private void requestSignatureUpdate() {
        try {
            if (!getServiceState(FRESHCLAM).running()) {
                updateStatus = "Fehler: FreshClam läuft nicht";
                notifyUpdate("Update nicht gestartet",
                        "Der Dienst clamav-freshclam läuft nicht.", true);
                return;
            }

            // SIGUSR1 weckt den laufenden FreshClam-Daemon zur Updatepruefung.
            // Keine zweite FreshClam-Instanz, kein Dienstneustart.
            // pkexec nutzt den KDE-Polkit-Agenten zur Passwortabfrage.
            CommandResult result = runCommand(List.of(
                    "pkexec", "/usr/bin/systemctl", "kill", "--kill-whom=main",
                    "--signal=SIGUSR1", FRESHCLAM), 120);

            if (result.exitCode() == 0) {
                updateStatus = "Updateprüfung angefordert";
                System.out.println("FreshClam wurde zur sofortigen Updateprüfung aufgefordert.");
                notifyUpdate("Updateprüfung gestartet",
                        "FreshClam prüft jetzt die Signaturdatenbanken."
                                + " Der Signaturstand wird anschließend erneut abgefragt.", false);
                // clamd kann die neue Signaturdatenbank erst etwas spaeter laden.
                scheduler.schedule(this::refresh, 15, TimeUnit.SECONDS);
                scheduler.schedule(this::refresh, 60, TimeUnit.SECONDS);
            } else {
                updateStatus = "Update konnte nicht gestartet werden";
                System.err.println("FreshClam-Updateanforderung fehlgeschlagen ("
                        + result.exitCode() + "): " + result.output());
                notifyUpdate("Update nicht gestartet",
                        "Autorisierung abgebrochen oder Befehl fehlgeschlagen."
                                + " Details stehen im Startterminal.", true);
            }
        } finally {
            maintenanceBusy.set(false);
            if (!scheduler.isShutdown()) scheduler.execute(this::refresh);
        }
    }

    private static void notifyUpdate(String title, String message, boolean error) {
        CommandResult result = runCommand(List.of("notify-send",
                "--app-name=ClamAV Tray", error ? "--urgency=critical" : "--urgency=normal",
                "--icon=" + (error ? "dialog-error" : "software-update-available"),
                "ClamAV: " + title, message));
        if (result.exitCode() != 0) {
            System.err.println("Desktop-Benachrichtigung fehlgeschlagen: " + result.output());
        }
    }

    private void startEventListener() {
        try {
            // Nur localhost. Das Helferskript sendet an exakt diese IPv4-Adresse.
            eventSocket = new DatagramSocket(new InetSocketAddress("127.0.0.1", VIRUS_EVENT_PORT));
            eventWorker.execute(this::receiveVirusEvents);
            System.out.println("Warte auf ClamAV-Virenfunde (127.0.0.1:" + VIRUS_EVENT_PORT + ").");
        } catch (IOException exception) {
            System.err.println("Virenfund-Empfang nicht verfügbar: " + exception.getMessage()
                    + " (läuft ClamAvTray bereits?).");
        }
    }

    private void receiveVirusEvents() {
        while (!eventSocket.isClosed()) {
            byte[] bytes = new byte[8192];
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
            try {
                eventSocket.receive(packet);
                if (!packet.getAddress().isLoopbackAddress()) continue;

                // Die beiden Felder sind NUL-getrennt, auch wenn Dateinamen
                // Leerzeichen, Anführungszeichen oder Zeilenumbrüche enthalten.
                String data = new String(packet.getData(), packet.getOffset(),
                        packet.getLength(), StandardCharsets.UTF_8);
                int firstEnd = data.indexOf('\0');
                int secondEnd = firstEnd < 0 ? -1 : data.indexOf('\0', firstEnd + 1);
                if (firstEnd < 0 || secondEnd < 0) continue;

                String fileName = cleanText(data.substring(0, firstEnd));
                String virusName = cleanText(data.substring(firstEnd + 1, secondEnd));
                recordDetection(fileName, virusName);
            } catch (SocketException exception) {
                if (!eventSocket.isClosed()) {
                    System.err.println("Virenfund-Empfang abgebrochen: " + exception.getMessage());
                }
                return;
            } catch (IOException exception) {
                System.err.println("Virenfund konnte nicht empfangen werden: " + exception.getMessage());
            }
        }
    }

    private void recordDetection(String fileName, String virusName) {
        long now = System.currentTimeMillis();
        Detection previous = lastDetection;
        if (previous != null && previous.fileName().equals(fileName)
                && previous.virusName().equals(virusName)
                && now - previous.timestamp() < DUPLICATE_INTERVAL_MS
                && alertUnconfirmed) {
            return;
        }

        lastDetection = new Detection(fileName, virusName, now);
        alertUnconfirmed = true;
        System.err.println("CLAMAV-FUND: " + virusName + " in " + fileName);
        scheduler.execute(this::refresh);

        // Laeuft unter dem angemeldeten KDE-Benutzer. Der clamd-Dienst muss
        // darum nicht auf die D-Bus-Sitzung des Benutzers zugreifen.
        CommandResult notification = runCommand(List.of(
                "notify-send", "--urgency=critical", "--icon=dialog-warning",
                "--app-name=ClamAV Tray", "ClamAV: Virenfund",
                "Erkennung: " + virusName + "\nDatei: " + fileName));
        if (notification.exitCode() != 0) {
            System.err.println("KDE-Benachrichtigung fehlgeschlagen: " + notification.output());
        }
    }

    private static String cleanText(String input) {
        String cleaned = input.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (cleaned.isBlank()) return "unbekannt";
        return shorten(cleaned, 300);
    }

    private static String shorten(String text, int maximumLength) {
        return text.length() <= maximumLength ? text
                : "…" + text.substring(text.length() - maximumLength + 1);
    }

    private void refresh() {
        try {
            ServiceState daemon = getServiceState(CLAMD);
            ServiceState freshclam = getServiceState(FRESHCLAM);
            ServiceState onAccess = getServiceState(CLAMONACC);
            SignatureState signature = getSignatureState();
            Detection detected = lastDetection;
            boolean pending = detected != null && alertUnconfirmed;

            boolean allRunning = daemon.running() && freshclam.running() && onAccess.running();
            boolean critical = !daemon.running() || !onAccess.running();
            boolean scanning = scanRunning;
            byte[] icon = allRunning && signature.fromDaemon()
                    ? iconGreen : critical ? iconRed : iconYellow;

            // Ein laufender manueller Scan ist gelb, aber ein kritischer
            // Dienstfehler oder ein offener Virenalarm bleiben rot.
            if (scanning && !critical) icon = iconYellow;
            if (pending) icon = iconRed;

            // Nur bei echten Statusaenderungen das Menue ersetzen. Das ist
            // wichtig, da libtray bei jedem setMenu neue D-Bus-IDs vergibt.
            String menuState = String.join("\u0001", daemon.label(), freshclam.label(),
                    onAccess.label(), signature.version(), signature.date(),
                    detected == null ? "" : detected.virusName(),
                    detected == null ? "" : detected.fileName(), Boolean.toString(pending),
                    updateStatus, restartStatus, scanStatus,
                    Boolean.toString(scanning), Boolean.toString(scanBusy.get()));
            if (!menuState.equals(currentMenuState)) {
                if (tray.setMenu(makeMenu(daemon.label(), freshclam.label(), onAccess.label(),
                        signature.version(), signature.date(), detected, pending))) {
                    currentMenuState = menuState;
                } else {
                    System.err.println("KDE-Kontextmenü konnte nicht aktualisiert werden.");
                }
            }
            tray.setIcon(icon);
            tray.setTooltip("ClamAV: " + (pending ? "VIRENFUND – Alarm bestätigen"
                    : critical ? "Dienst prüfen"
                    : scanning ? "SCAN LÄUFT – " + shorten(scanStatus, 53)
                    : allRunning ? "Dienste aktiv" : "Dienst prüfen")
                    + " | Signaturen: " + signature.version());
        } catch (Exception exception) {
            tray.setIcon(iconRed);
            tray.setTooltip("ClamAV: Statusabfrage fehlgeschlagen");
            System.err.println("Statusabfrage fehlgeschlagen: " + exception.getMessage());
        }
    }

    private static ServiceState getServiceState(String unit) {
        CommandResult result = runCommand(List.of(
                "systemctl", "show", "--no-pager", "--property=LoadState,ActiveState", unit));
        if (result.exitCode() != 0) {
            return new ServiceState("unbekannt", "unbekannt");
        }

        String loadState = "unbekannt";
        String activeState = "unbekannt";
        for (String line : result.output().split("\\R")) {
            if (line.startsWith("LoadState=")) {
                loadState = line.substring("LoadState=".length());
            } else if (line.startsWith("ActiveState=")) {
                activeState = line.substring("ActiveState=".length());
            }
        }
        return new ServiceState(loadState, activeState);
    }

    private static SignatureState getSignatureState() {
        // clamdscan --version zeigt die vom laufenden clamd geladene Datenbank.
        CommandResult daemonVersion = runCommand(List.of("clamdscan", "--version"));
        SignatureState loaded = parseSignature(daemonVersion, true);
        if (loaded != null) {
            return loaded;
        }

        // Fallback: lokale Datenbank, nicht zwingend schon von clamd geladen.
        CommandResult installedVersion = runCommand(List.of("clamscan", "--version"));
        SignatureState installed = parseSignature(installedVersion, false);
        return installed != null ? installed : new SignatureState("unbekannt", "unbekannt", false);
    }

    private static SignatureState parseSignature(CommandResult result, boolean fromDaemon) {
        if (result.exitCode() != 0 || result.output().isBlank()) {
            return null;
        }
        // Beispiel: ClamAV 1.4.3/27600/Tue Jan 1 12:00:00 2026
        String firstLine = result.output().split("\\R", 2)[0].trim();
        String[] parts = firstLine.split("/", 3);
        if (parts.length < 3 || !parts[1].matches("\\d+")) {
            return null;
        }
        return new SignatureState(parts[1] + (fromDaemon ? " (clamd)" : " (lokal)"),
                parts[2], fromDaemon);
    }

    private static CommandResult runCommand(List<String> command) {
        return runCommand(command, 5);
    }

    private static CommandResult runCommand(List<String> command, int timeoutSeconds) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new CommandResult(-1, "Zeitüberschreitung");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new CommandResult(process.exitValue(), output.trim());
        } catch (IOException exception) {
            return new CommandResult(-1, "Befehl nicht gefunden oder nicht ausführbar");
        } catch (InterruptedException exception) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "Unterbrochen");
        }
    }

    private static BufferedImage readLogo() throws IOException {
        try (InputStream resource = ClamAvTray.class.getResourceAsStream("/clamav-logo.png")) {
            if (resource != null) {
                BufferedImage image = ImageIO.read(resource);
                if (image != null) return image;
            }
        }
        // Erleichtert das Starten ohne Maven aus dem Quellordner.
        try (InputStream file = Files.newInputStream(Path.of("clamav-logo.png"))) {
            BufferedImage image = ImageIO.read(file);
            if (image == null) throw new IOException("PNG ist beschädigt");
            return image;
        }
    }

    /** Echter farbiger PNG-Kreis für KDE-DBusMenu (unabhängig von Emoji-Fonts). */
    private static byte[] makeStatusDot(Color color) throws IOException {
        int size = 22;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(color.darker());
            graphics.fillOval(2, 2, size - 4, size - 4);
            graphics.setColor(color);
            graphics.fillOval(3, 3, size - 6, size - 6);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) throw new IOException("PNG-Encoder fehlt");
        return bytes.toByteArray();
    }

    private static byte[] makeDevilIcon(BufferedImage source, float targetHue) throws IOException {
        // Original von Cisco-Talos: 250x250 Pixel, Motiv innerhalb des weissen Randes.
        int x = Math.round(source.getWidth() * 0.108f);
        int y = Math.round(source.getHeight() * 0.068f);
        int size = Math.round(source.getWidth() * 0.78f);
        BufferedImage cropped = source.getSubimage(x, y, size, size);

        BufferedImage recolored = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                int argb = cropped.getRGB(px, py);
                int alpha = (argb >>> 24) & 0xff;
                int red = (argb >>> 16) & 0xff;
                int green = (argb >>> 8) & 0xff;
                int blue = argb & 0xff;

                // Nur die roten Teile der Figur färben. Augen, Hörner und Hintergrund bleiben.
                if (red >= 100 && red > green * 1.4 && red > blue * 1.25 && red - green > 35) {
                    float[] hsb = Color.RGBtoHSB(red, green, blue, null);
                    int rgb = Color.HSBtoRGB(targetHue, hsb[1], hsb[2]);
                    argb = (alpha << 24) | (rgb & 0x00ffffff);
                }
                recolored.setRGB(px, py, argb);
            }
        }

        BufferedImage scaled = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(recolored, 0, 0, 128, 128, null);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!ImageIO.write(scaled, "png", buffer)) throw new IOException("PNG-Encoder fehlt");
        return buffer.toByteArray();
    }

    private record ServiceState(String loadState, String activeState) {
        boolean running() {
            return "active".equals(activeState);
        }

        String label() {
            if ("not-found".equals(loadState)) {
                return "nicht installiert";
            }
            return switch (activeState) {
                case "active" -> "läuft";
                case "inactive" -> "inaktiv";
                case "failed" -> "FEHLER";
                case "activating" -> "startet";
                case "deactivating" -> "stoppt";
                default -> "unbekannt";
            };
        }
    }

    private record SignatureState(String version, String date, boolean fromDaemon) {}
    private record Detection(String fileName, String virusName, long timestamp) {}
    private record CommandResult(int exitCode, String output) {}
}
