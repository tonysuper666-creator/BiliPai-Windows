#!/bin/sh
# Debian 13 (trixie). Run with sudo. Official sources only: Debian apt + Adoptium (Temurin) apt repo.
set -eu
apt-get update
apt-get install -y --no-install-recommends ca-certificates wget gnupg git python3 time
install -d -m 0755 /etc/apt/trusted.gpg.d
wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor > /etc/apt/trusted.gpg.d/adoptium.gpg
. /etc/os-release
echo "deb https://packages.adoptium.net/artifactory/deb ${VERSION_CODENAME} main" > /etc/apt/sources.list.d/adoptium.list
apt-get update
apt-get install -y temurin-21-jdk
java -version
python3 --version   # validated with 3.13.5
