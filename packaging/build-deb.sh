#!/usr/bin/env bash
# Erstellt lokal ein installierbares Debian-13-Paket inkl. Java-25-Laufzeit.
# Keine Root-Rechte zum Bauen erforderlich.
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_version="1.5.2"
deb_version="${app_version}-1"
deb_arch="$(dpkg --print-architecture)"
jar_file="${project_dir}/target/clamav-tray-${app_version}.jar"
output_dir="${project_dir}/dist"

if [[ $# -gt 1 || ( $# -eq 1 && "${1}" != "--use-existing-jar" ) ]]; then
    printf 'Verwendung: %s [--use-existing-jar]\n' "$0" >&2
    exit 2
fi

required_tools=(dpkg-deb readlink)
if [[ "${1:-}" != "--use-existing-jar" ]]; then
    required_tools+=(mvn)
fi
for program in "${required_tools[@]}"; do
    if ! command -v "$program" >/dev/null 2>&1; then
        printf 'Fehlendes Build-Werkzeug: %s\n' "$program" >&2
        exit 1
    fi
done

# Maven und Gradle sollen garantiert das JDK 25 verwenden.
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/jlink" ]]; then
    build_jdk="${JAVA_HOME}"
elif command -v jlink >/dev/null 2>&1; then
    build_jdk="$(dirname "$(dirname "$(readlink -f "$(command -v jlink)")")")"
else
    printf 'JDK 25 mit jlink fehlt. JAVA_HOME auf die JDK-25-Installation setzen.\n' >&2
    exit 1
fi

if [[ ! -x "${build_jdk}/bin/javac" || ! -x "${build_jdk}/bin/jlink" ]] ||
   ! "${build_jdk}/bin/java" -version 2>&1 | grep -Eq 'version "25([.\"]|$)'; then
    printf 'JAVA_HOME (%s) muss auf ein vollständiges JDK 25 zeigen.\n' "$build_jdk" >&2
    exit 1
fi

export JAVA_HOME="$build_jdk"
export PATH="${JAVA_HOME}/bin:${PATH}"

if [[ "${1:-}" != "--use-existing-jar" ]]; then
    printf '\n[1/4] libtray lokal kompilieren ...\n'
    (cd "${project_dir}/libtray_reference" && ./gradlew publishToMavenLocal --no-configuration-cache)
    printf '\n[2/4] ClamAV-Tray mit Maven bauen ...\n'
    (cd "$project_dir" && mvn -DskipTests clean package)
else
    printf '\n[1-2/4] Vorhandene JAR verwenden ...\n'
fi

if [[ ! -s "$jar_file" ]]; then
    printf 'JAR nicht gefunden: %s\n' "$jar_file" >&2
    printf 'Zuerst Maven bauen oder --use-existing-jar entfernen.\n' >&2
    exit 1
fi

# Jeder Arbeitsordner ist frisch und liegt außerhalb des Projektbaums.
working_dir="$(mktemp -d)"
cleanup() {
    if [[ -n "${working_dir:-}" && -d "$working_dir" ]]; then
        rm -r -- "$working_dir"
    fi
}
trap cleanup EXIT

package_root="${working_dir}/clamav-tray"
install_root="${package_root}/usr/lib/clamav-tray"
mkdir -p "${package_root}/DEBIAN" "${install_root}" \
         "${package_root}/usr/bin" \
         "${package_root}/usr/share/applications" \
         "${package_root}/usr/share/pixmaps" \
         "${package_root}/usr/share/doc/clamav-tray" \
         "${package_root}/etc/xdg/autostart"

printf '\n[3/4] Kompakte Java-25-Laufzeit erstellen ...\n'
"${JAVA_HOME}/bin/jlink" \
    --module-path "${JAVA_HOME}/jmods" \
    --add-modules java.desktop,java.logging,jdk.unsupported,jdk.crypto.ec \
    --strip-debug --no-man-pages --no-header-files \
    --output "${install_root}/runtime"

install -m 0644 "$jar_file" "${install_root}/clamav-tray.jar"
install -m 0755 "${project_dir}/clamav-tray-event.sh" "${install_root}/clamav-tray-event.sh"
install -m 0755 "${project_dir}/packaging/clamav-tray" "${package_root}/usr/bin/clamav-tray"
install -m 0644 "${project_dir}/packaging/clamav-tray.desktop" "${package_root}/usr/share/applications/clamav-tray.desktop"
install -m 0644 "${project_dir}/packaging/clamav-tray.desktop" "${package_root}/etc/xdg/autostart/clamav-tray.desktop"
install -m 0644 "${project_dir}/clamav-logo.png" "${package_root}/usr/share/pixmaps/clamav-tray.png"
install -m 0644 "${project_dir}/README.md" "${package_root}/usr/share/doc/clamav-tray/README.md"
install -m 0644 "${project_dir}/README-ClamAV-Tray.md" "${package_root}/usr/share/doc/clamav-tray/README-ClamAV-Tray.md"
install -m 0644 "${project_dir}/packaging/copyright" "${package_root}/usr/share/doc/clamav-tray/copyright"

sed -e "s/@VERSION@/${deb_version}/g" -e "s/@ARCH@/${deb_arch}/g" \
    "${project_dir}/packaging/control.template" > "${package_root}/DEBIAN/control"

mkdir -p "$output_dir"
deb_file="${output_dir}/clamav-tray_${deb_version}_${deb_arch}.deb"
printf '\n[4/4] Debian-Paket erstellen ...\n'
dpkg-deb --build --root-owner-group "$package_root" "$deb_file"
dpkg-deb --info "$deb_file"
printf '\nFertig: %s\n' "$deb_file"
printf 'Installieren: sudo apt install %s\n' "$deb_file"
