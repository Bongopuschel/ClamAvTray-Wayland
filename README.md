# ClamAV Tray für Debian 13 und KDE Plasma (Wayland)

Inoffizielle Statusanzeige für eine bestehende ClamAV-Installation. Das Tray
zeigt den Status von `clamav-daemon`, `clamav-freshclam` und
`clamav-clamonacc`, die geladenen Signaturen und Virenfunde. Du kannst
Dateien und Ordner über `kdialog` auswählen und mit `clamscan` prüfen.

Der ClamAV-Teufel wechselt seine Farbe: **grün** bei normalem Betrieb,
**gelb** bei einer Warnung oder einem laufenden manuellen Scan und **rot**
bei kritischen Fehlern oder unbestätigten Virenfunden.

## Option A: Als Debian-Paket installieren (empfohlen)

Das Build-Skript erzeugt ein `.deb` mit der Anwendung **und einer eigenen
Java-25-Laufzeit**. Auf dem Rechner, auf dem du es später installierst,
brauchst du deshalb weder Maven noch Gradle noch ein installiertes Java.

### 1. Voraussetzungen auf dem Build-Rechner

- Debian 13 (amd64 oder arm64) mit Internetzugang.
- Vollständiges **JDK 25** mit den Befehlen `javac` und `jlink`.
- Maven und `dpkg-deb`.

Die benötigten Debian-Build-Werkzeuge installieren:

```bash
sudo apt update
sudo apt install maven dpkg-dev
```

Prüfen, ob JDK 25 aktiv ist:

```bash
java -version
javac -version
jlink --version
```

Falls mehrere JDKs installiert sind, setze `JAVA_HOME` auf dein **JDK 25**.
Der genaue Pfad hängt von deiner Java-Installation ab. Das Build-Skript
prüft die Version und bricht ab, wenn sie nicht passt.

### 2. Projekt herunterladen und `.deb` bauen

Dieses Projekt aus GitHub klonen (oder das bereitgestellte ZIP entpacken),
in das Projektverzeichnis wechseln und ausführen:

```bash
./packaging/build-deb.sh
```

Das Skript baut erst die angepasste `libtray`-Bibliothek mit dem mitgelieferten
Gradle-Wrapper, dann die Anwendung mit Maven, erzeugt mit `jlink` eine passende
Java-25-Laufzeit und packt alles mit `dpkg-deb` ein. Du benötigst dafür **kein
`sudo`**. Beim ersten Durchlauf werden Build-Abhängigkeiten heruntergeladen.

Wenn die JAR-Datei bereits gebaut wurde und du nur das Debian-Paket neu
erstellen möchtest:

```bash
./packaging/build-deb.sh --use-existing-jar
```

Das Ergebnis liegt unter:

```text
dist/clamav-tray_1.5.2-1_amd64.deb
```

Auf arm64 endet der Dateiname entsprechend auf `_arm64.deb`.

### 3. Auf dem Zielrechner installieren

Die erstellte `.deb`-Datei auf den gewünschten Debian-13-Rechner kopieren.
Dann im Verzeichnis mit der Datei:

```bash
sudo apt install ./clamav-tray_1.5.2-1_amd64.deb
```

Das ist ein Beispiel für amd64; gegebenenfalls den Dateinamen anpassen.

**Wichtig:** `apt install ./datei.deb` löst benötigte Debian-Abhängigkeiten
automatisch auf. Das Paket deklariert unter anderem:

- `clamav`, `clamav-daemon`, `clamav-freshclam`, `clamdscan`
- `kdialog`, `libnotify-bin`, `pkexec`
- `libdbus-1-3` sowie benötigte Grafikbibliotheken

Eine zusätzliche Java-Installation ist nicht erforderlich. **ClamAV selbst
wird nicht im Paket gebündelt**, sondern von APT aus Debian installiert.
Eine bestehende ClamAV-Konfiguration wird dabei nicht durch unser Paket
geändert.

### 4. Tray starten

Nach dem nächsten KDE-Login startet die Tray-Anzeige automatisch. Sofort
starten kannst du sie auch über das Anwendungsmenü oder im Terminal:

```bash
clamav-tray
```

Starte nicht zwei Exemplare gleichzeitig: Nur eines kann den lokalen
Virenfund-Port `127.0.0.1:47619` belegen.

### 5. Benachrichtigungen über On-Access-Funde aktivieren

Der On-Access-Scanner muss mit `VirusEvent` die Benachrichtigung an das Tray
weitergeben. Das Paket installiert dafür dieses Helferskript:

```text
/usr/lib/clamav-tray/clamav-tray-event.sh
```

Öffne die Konfiguration:

```bash
sudoedit /etc/clamav/clamd.conf
```

Folgende Zeile eintragen oder eine vorhandene `VirusEvent`-Zeile entsprechend
anpassen:

```ini
VirusEvent /usr/lib/clamav-tray/clamav-tray-event.sh
```

Anschließend die betroffenen Dienste neu starten:

```bash
sudo systemctl restart clamav-daemon clamav-clamonacc
```

Test ohne Schadsoftware:

```bash
CLAM_VIRUSEVENT_FILENAME="/tmp/testdatei" \
CLAM_VIRUSEVENT_VIRUSNAME="TESTMELDUNG" \
/usr/lib/clamav-tray/clamav-tray-event.sh
```

Das Tray sollte eine rote Warnung anzeigen, die sich bestätigen lässt.
Bestätigen bedeutet nur, dass du die Meldung gesehen hast – die Datei wird
weder gelöscht noch freigegeben.

## Option B: Direkt aus dem Quellcode starten

Wenn du noch kein Debian-Paket bauen möchtest, kannst du die Anwendung
auch mit dem bereits vorhandenen JDK 25 starten:

```bash
cd libtray_reference
./gradlew publishToMavenLocal --no-configuration-cache
cd ..
mvn -DskipTests clean package
java --enable-native-access=ALL-UNNAMED -jar target/clamav-tray-1.5.2.jar
```

Wichtig: Der lokale `libtray`-Fork muss einmal gebaut werden, weil er
farbige PNG-Statussymbole im nativen KDE-Kontextmenü ermöglicht.

## Deinstallieren

```bash
sudo apt remove clamav-tray
```

Die ClamAV-Dienste und deren Datenbanken bleiben bestehen. Falls du
`VirusEvent` wie oben manuell auf das Tray-Helferskript umgestellt hast,
entferne beziehungsweise korrigiere diese Zeile **vor der Deinstallation**.

## Sicherheit und Einschränkungen

- Manuelle Scans laufen als angemeldeter Benutzer und ändern keine Dateien.
- Virenfunde werden nur angezeigt und quittiert, nicht automatisch bereinigt.
- Das Tray verändert keine ClamAV-Konfiguration und startet keine Dienste
  ohne ausdrücklichen Menüaufruf.
- Der lokale UDP-Empfang für Virenfunde ist nicht authentifiziert und für
  Rechner mit mehreren parallelen Benutzersitzungen nur eingeschränkt geeignet.
- Ein grünes Symbol sagt aus, dass die geprüften Dienste laufen; es ist
  **keine Garantie**, dass jede Datei tatsächlich überwacht wird.

Ausführliche technische Beschreibung: [README-ClamAV-Tray.md](README-ClamAV-Tray.md).

Das Projekt ist keine offizielle Anwendung des ClamAV-Teams.
