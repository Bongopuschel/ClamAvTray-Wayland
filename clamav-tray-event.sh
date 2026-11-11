#!/usr/bin/env bash
# Von clamd per "VirusEvent /usr/local/bin/clamav-tray-event.sh" gestartet.
# Kein direkter Zugriff des clamd-Dienstes auf die KDE-Benutzersitzung.

# Die Werte werden ausschliesslich als Daten gesendet, niemals als Shell-Befehl.
# UDP-Datagramme werden nur an das lokale Java-Programm weitergereicht.
printf '%s\0%s\0' \
    "${CLAM_VIRUSEVENT_FILENAME:-unbekannt}" \
    "${CLAM_VIRUSEVENT_VIRUSNAME:-unbekannt}" \
    > /dev/udp/127.0.0.1/47619
