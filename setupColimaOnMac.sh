#!/usr/bin/env bash

# Stjålet fra https://medium.com/@guillem.riera/the-most-performant-docker-setup-on-macos-apple-silicon-m1-m2-m3-for-x64-amd64-compatibility-da5100e2557d

colima start \
  --profile default \
  --activate \
  --arch aarch64 \
  --cpu 10 \
  --disk 48 \
  --memory 24 \
  --mount ${HOME}:w \
  --mount-inotify \
  --ssh-agent \
  --vm-type vz \
  --vz-rosetta \
  --verbose

cat <<'EOF'
Colima skal nå være konfigurert med en virtuell maskin som kan bruke Apple sitt
Hypervisor.Framework og Rosetta til å kjøre containere som ikke finnes i
ARM64-versjon.
For å få 'docker' til å jobbe inne i denne virtuelle maskinen, må du legge
følgende environment-variable inn i shellet ditt (eller en shell-oppstartsfil
som ~/.zshrc):

  export COLIMA_VM="default"
  export COLIMA_VM_SOCKET="${HOME}/.colima/${COLIMA_VM}/docker.sock"
  export DOCKER_HOST="unix://${COLIMA_VM_SOCKET}"
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"

Dette må gjøres på en måte som også IntelliJ ser dersom man skal kunne starte
tester derfra.
EOF
