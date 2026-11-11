# ClamAV-Tray für KDE Plasma / Wayland

Eine kleine Statusanzeige in Java 25. Das ClamAV-Teufelslogo wechselt je nach Status
seine Farbe. Das Kontextmenü zeigt die drei ClamAV-Systemdienste und die
Signaturversion mit Datum. Neue Virenfunde lösen eine KDE-Benachrichtigung aus,
bis zur Bestätigung bleibt das Symbol rot. Das Menü kann eine sofortige
Signaturprüfung anstoßen, alle drei ClamAV-Dienste neu starten und zeigt die
Versionsnummer der Tray-Anwendung an. Grüne und rote Statuspunkte markieren
laufende bzw. nicht laufende Dienste im KDE-Kontextmenü. Über native KDE-
Dateiauswahlfenster können einzelne Dateien und ganze Ordner per `clamscan`
untersucht werden.

**Installation als `.deb`:** Im Projekt liegt jetzt `packaging/build-deb.sh`.
Auf einem Build-Rechner mit JDK 25 und Maven erzeugt es ein Debian-Paket,
das die Java-Laufzeit mitbringt und die Systemabhängigkeiten über APT
installieren lässt. Die vollständige Schritt-für-Schritt-Anleitung steht
in [README.md](README.md).

## Voraussetzungen (Debian 13)

- Java 25 JDK (`java -version`, `javac -version`)
- Maven (`sudo apt install maven`)
- `clamscan` (`sudo apt install clamav`)
- `kdialog` für die Datei-/Ordnerauswahl (`sudo apt install kdialog`)
- Internetzugang beim ersten Bau für den beigefügten Gradle-Wrapper und Maven
- Laufende ClamAV-Dienste und `clamdscan` (für die vom Daemon geladene Signaturversion)
- KDE Plasma mit einer laufenden D-Bus-Benutzersitzung
- `notify-send` für die Desktop-Benachrichtigungen (`sudo apt install libnotify-bin`)
- `pkexec` und ein funktionierender KDE-Polkit-Agent für die manuelle
  Updateanforderung und den Dienstneustart (`sudo apt install pkexec`)

## Bauen und starten

Ab Version 1.5.0 enthält das Paket eine kleine, lokal angepasste Version von
`libtray`. Sie überträgt echte PNG-Bilder für Statuspunkte an das KDE-Menü.
Die Standardbibliothek 0.2.0 kann das noch nicht. **Einmalig zuerst diese
Bibliothek lokal bauen:**

```bash
cd libtray_reference
./gradlew publishToMavenLocal --no-configuration-cache
cd ..
```

Der Gradle-Wrapper lädt selbst die benötigte Version. Du musst `gradle` nicht
systemweit installieren. Die erzeugte Bibliothek landet unter
`~/.m2/repository/local/clamav/libtray/` und bleibt nur auf deinem Rechner.
Die lokale Build-Konfiguration ist direkt `build.gradle.kts`.
Die ursprüngliche Upstream-Konfiguration ist unter `build-upstream.gradle.kts`
gesichert. Die mit Gradle 9 entfernte Option `-b` wird nicht benötigt.
Danach wie gewohnt mit Maven bauen und starten:

```bash
mvn -DskipTests package
java --enable-native-access=ALL-UNNAMED -jar target/clamav-tray-1.5.2.jar
```

Maven lädt beim ersten Durchlauf die Java-Abhängigkeiten herunter. Das Programm
benötigt beim Start keine Root-Rechte. Es verändert keine Systemdienste.
Die Maven-Konfiguration kompiliert ausdrücklich nur `ClamAvTray.java`.
Die Java-Testdateien in `libtray_reference/src/test/` werden nicht mitgebaut;
das separate Bibliotheksprojekt nutzt dafür Gradle.

Vor dem Start der neuen Version die alte Tray-Anwendung beenden, damit nicht zwei
Symbole gleichzeitig angezeigt werden.

## Farben

- Grün: alle drei Dienste aktiv und geladene Signaturversion lesbar
- Gelb: FreshClam nicht aktiv oder geladene Signaturversion nicht lesbar
- Gelb: ein manueller Scan läuft gerade (sofern kein kritischer Fehler vorliegt)
- Rot: `clamav-daemon` oder `clamav-clamonacc` nicht aktiv, **oder ein neuer
  Virenfund wurde noch nicht bestätigt**

Ein bestätigter Fund bleibt unter "Letzter Fund" im Kontextmenü sichtbar.
"Virenalarm bestätigen" entfernt nur die rote Alarmanzeige; die betroffene Datei
wird **nicht** verändert oder freigegeben. Der Menüpunkt ist immer anklickbar,
damit Plasma ihn nicht als dauerhaft ausgegraut zwischenspeichert. Ohne Fund
oder nach einer Bestätigung bewirkt ein weiterer Klick nichts.
Der zusätzliche Menüeintrag "Alarmstatus" zeigt "OFFEN" oder "bestätigt".

## Virenfund-Benachrichtigung einrichten

ClamAV kann bei einem Fund mit `VirusEvent` ein Skript ausführen. Der ClamAV-Dienst
übermittelt Dateipfad und Signaturnamen an `clamav-tray-event.sh`. Das Skript sendet
die Daten über einen UDP-Port auf localhost an die laufende Java-Anwendung. **Die
Java-Anwendung zeigt die Benachrichtigung in deiner KDE-Sitzung an.**

Das Ereignisskript benötigt selbst keine Root-Rechte. Für die einmalige Installation
und die Änderung der clamd-Konfiguration werden Root-Rechte benötigt:

```bash
sudo apt install libnotify-bin
sudo install -m 0755 clamav-tray-event.sh /usr/local/bin/clamav-tray-event.sh
sudoedit /etc/clamav/clamd.conf
```

In der Konfiguration die folgende Zeile ergänzen (falls bereits eine aktive
`VirusEvent`-Zeile existiert, nicht einfach eine zweite hinzufügen, sondern die
Konfiguration gezielt anpassen):

```ini
VirusEvent /usr/local/bin/clamav-tray-event.sh
```

Anschließend den ClamAV-Daemon neu starten (währenddessen kurz kein On-Access-Scan):

```bash
sudo systemctl restart clamav-daemon
sudo systemctl restart clamav-clamonacc
```

**Test ohne echte Schadsoftware:** Die Java-Anwendung muss gestartet sein. Dann
im Terminal folgendes ausführen:

```bash
CLAM_VIRUSEVENT_FILENAME="/tmp/clamav-testdatei" \
CLAM_VIRUSEVENT_VIRUSNAME="TEST-BENACHRICHTIGUNG" \
/usr/local/bin/clamav-tray-event.sh
```

Erwartet: eine KDE-Benachrichtigung, rotes ClamAV-Logo, Einträge "Letzter Fund"
und "Datei" im Tray-Menü. Mit "Virenalarm bestätigen" sollte sich das Symbol
wieder an den Dienststatus anpassen. Dieser Test prüft den Meldeweg, **noch nicht
einen echten ClamAV-Dateiscan**.

### Grenzen dieser ersten Version

- Die Java-Anwendung muss bei einem Fund laufen. Ohne laufende GUI werden
  Ereignisse nicht gespeichert und später nicht nachgemeldet.
- Der lokale UDP-Port 47619 ist nicht authentifiziert: andere Programme auf
  demselben Rechner könnten Testmeldungen einspeisen. Es werden dadurch keine
  Dateien geändert und keine Befehle aus den übermittelten Namen ausgeführt.
- Die Anwendung zeigt Virenfunde an, verschiebt oder löscht **keine** Dateien.
- Sie zeigt Benachrichtigungen für Funde an, die `clamd` tatsächlich meldet;
  es ist kein zusätzlicher Scan-Mechanismus.
- Wenn der Port bereits durch eine weitere Instanz belegt ist, wird dies
  im Startterminal ausgegeben. Vor dem Start die alte Instanz beenden.

Die Farbe ist eine Zustandsanzeige, keine Garantie für Malwarefreiheit.
Die angezeigte Signaturversion ist lokal; es erfolgt keine Abfrage, ob online
bereits eine neuere Version verfügbar ist.

## Dienste im Kontextmenü neu starten

Die drei Dienstzeilen erhalten jetzt **echte PNG-Punkte**, keine Emojis mehr:

- Grün: Dienst aktiv
- Rot: Dienst inaktiv, fehlerhaft oder nicht gefunden
- Gelb: Status wird gerade geprüft oder ist unbekannt

Die drei Statuszeilen sind im KDE-Menü technisch anklickbar, weil Plasma
deaktivierte Einträge einschließlich der farbigen Emojis grau darstellt.
Ein Klick auf eine Statuszeile löst keine Aktion aus.

Der Menüpunkt "Alle Dienste neu starten" führt nach einer grafischen
Administratorabfrage folgenden Befehl aus:

```bash
pkexec /usr/bin/systemctl restart clamav-daemon.service clamav-freshclam.service clamav-clamonacc.service
```

Während des Neustarts ist der On-Access-Schutz kurzzeitig nicht verfügbar.
Ein laufender Signaturupdate-Auftrag und ein Dienstneustart werden nicht
gleichzeitig gestartet. Der Menüeintrag "Neustart" zeigt den letzten
Anforderungsstatus; nach Abschluss werden die Dienste direkt und nochmals
nach 5 und 20 Sekunden überprüft. Der angezeigte Dienststatus entscheidet,
ob sie wirklich laufen, nicht allein die Rückmeldung des Neustartbefehls.

Bei Fehlern zeigt das Startterminal die Ausgabe von `systemctl`; zusätzlich:

```bash
systemctl --no-pager --full status clamav-daemon clamav-freshclam clamav-clamonacc
```

Die Schriftfarbe der ganzen Zeile bleibt systemabhängig. Die Punktbilder
werden als `icon-data` des D-Bus-Menüprotokolls an KDE übergeben und sollen
daher unabhängig von installierten Emoji-Schriften farbig erscheinen.

## Dateien oder Ordner manuell scannen

Im Kontextmenü gibt es:

- **Datei scannen …**: öffnet `kdialog --getopenfilename`.
- **Ordner scannen …**: öffnet `kdialog --getexistingdirectory` und scannt
  den Inhalt rekursiv.
- **Laufenden Scan abbrechen**: beendet bei Bedarf den aktuellen Scan.
- **SCAN LÄUFT** mit gelbem Punkt: während der Prüfung der aktuelle Pfad.
- **Letzter Scan**: nach Abschluss das Ergebnis der letzten Prüfung.

Der Scanner läuft als normaler Benutzer in einem eigenen Hintergrundthread.
Das Tray bleibt benutzbar. Die Dateien werden unverändert gelassen; es werden
**weder Lösch- noch Quarantäneoptionen** an `clamscan` übergeben. Die
Untersuchung selbst verwendet `clamscan --stdout --infected` und für
Verzeichnisse zusätzlich `--recursive` (ohne Symlink-Verfolgung).
Bei laufendem Scan wird auch der ClamAV-Teufel im Systemtray gelb und
der Tooltip zeigt deutlich `SCAN LÄUFT`. Offene Virenalarme und kritische
Dienstfehler haben Vorrang: Der Teufel bleibt dann rot.
Die Dateiauswahl liest ausschließlich die Standardausgabe von `kdialog`,
damit Qt-Warnungen nicht als Teil des Dateipfads interpretiert werden.
Lokale `file://`-Adressen werden ebenfalls unterstützt.

Ein Fund löst dieselbe rote Alarmanzeige und KDE-Benachrichtigung wie ein
On-Access-Fund aus; er kann über **Virenalarm bestätigen** quittiert werden.
Ein sauberer Scan wird ebenfalls per KDE-Benachrichtigung gemeldet. Details
und mögliche Lesefehler stehen im Startterminal. Der Scan kann nur Dateien
lesen, auf die der angemeldete Benutzer Zugriffsrechte besitzt.

## Manuelles Signaturupdate

Im Kontextmenü "Signaturen jetzt aktualisieren" wählen. KDE fordert bei Bedarf
das Administratorpasswort an. Die Java-Anwendung verwendet dafür:

```bash
pkexec /usr/bin/systemctl kill --kill-whom=main --signal=SIGUSR1 clamav-freshclam.service
```

`SIGUSR1` weckt den **bereits laufenden** FreshClam-Dienst zu einer sofortigen
Updateprüfung. Der Dienst wird nicht gestoppt oder neu gestartet; eine zweite
FreshClam-Instanz wird nicht gestartet. Der Menüpunkt "Update" zeigt an, ob die
Anforderung gesendet werden konnte. Eine erfolgreiche Anforderung bedeutet
**noch nicht**, dass eine neue Signatur heruntergeladen wurde. Der bereits
geladene Signaturstand wird nach 15 und 60 Sekunden erneut abgefragt.

Der Infobereich am Ende des Kontextmenüs zeigt "ClamAV-Tray: Version 1.5.2".

Falls die Updateanforderung scheitert:

```bash
journalctl -u clamav-freshclam -n 50 --no-pager
```

Wenn keine grafische Passwortabfrage erscheint, prüfen, ob `pkexec` und der
KDE-Polkit-Agent installiert sind. Die Statusanzeige selbst benötigt weiterhin
keine Root-Rechte.

## Fehlerdiagnose

```bash
systemctl is-active clamav-daemon clamav-freshclam clamav-clamonacc
clamdscan --version
echo "$XDG_SESSION_TYPE"
```

Wenn der Menüeintrag nicht erscheint, die Terminalausgabe beim Start sichern.
Die Tray-Funktion verwendet die lokale, angepasste `libtray` und über D-Bus das
KDE-StatusNotifierItem- und DBusMenu-Protokoll.

Wenn der Test keinen Alarm auslöst: Ist der Port in der Java-Ausgabe als aktiv
gemeldet? Sind `notify-send` und die KDE-Benachrichtigungen verfügbar?
Zur Prüfung der `VirusEvent`-Zeile:

```bash
grep -n '^VirusEvent' /etc/clamav/clamd.conf
journalctl -u clamav-daemon -n 40 --no-pager
```

## Logo und Lizenzen

`clamav-logo.png` stammt aus dem offiziellen ClamAV-Projekt von Cisco Talos:
https://github.com/Cisco-Talos/clamav/blob/main/logo.png

Das Logo wird zur Laufzeit für die Statusfarben verändert. Der verwendete
Quellstand ist unter GPLv2 veröffentlicht; die Originaldatei ist unverändert
mitgeliefert. Die Tray-Bibliothek `libtray` ist Apache-2.0-lizenziert.
