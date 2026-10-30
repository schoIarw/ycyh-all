#!/usr/bin/env bash
# Creates an isolated loopback-only sshd fixture. Never uses your existing SSH keys/config.
set -Eeuo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
FIXTURE=$(mktemp -d)
SSHD_PID=''
FIXTURE_USER=$(id -un)
cleanup(){ local code=$?;if [[ "$code" -ne 0 ]];then tail -n 30 "$FIXTURE/sshd.log" >&2;fi;if [[ -n "$SSHD_PID" ]];then kill "$SSHD_PID" 2>/dev/null || true;fi;rm -rf -- "$FIXTURE"; }
trap cleanup EXIT
command -v sshd >/dev/null
ssh-keygen -q -t ed25519 -N '' -f "$FIXTURE/host"
ssh-keygen -q -t ed25519 -N '' -f "$FIXTURE/client"
cp "$FIXTURE/client.pub" "$FIXTURE/authorized_keys"
PORT=${YCYH_SSH_PORT:-22229}
cat > "$FIXTURE/sshd_config" <<CFG
ListenAddress 127.0.0.1
Port $PORT
HostKey $FIXTURE/host
PidFile $FIXTURE/sshd.pid
AuthorizedKeysFile $FIXTURE/authorized_keys
StrictModes no
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
PermitRootLogin prohibit-password
UsePAM no
AllowUsers ${FIXTURE_USER}
CFG
printf '[127.0.0.1]:%s %s\n' "$PORT" "$(cat "$FIXTURE/host.pub")" > "$FIXTURE/known_hosts"
# Some Linux images need /run/sshd created by an administrator before this fixture.
"$(command -v sshd)" -D -e -f "$FIXTURE/sshd_config" > "$FIXTURE/sshd.log" 2>&1 &
SSHD_PID=$!
export YCYH_SSH_TEST=true YCYH_SSH_USER="${FIXTURE_USER}" YCYH_SSH_KEY="$FIXTURE/client" YCYH_SSH_KNOWN_HOSTS="$FIXTURE/known_hosts" YCYH_SSH_PORT="$PORT"
for i in $(seq 1 30);do if ssh -T -p "$PORT" -i "$YCYH_SSH_KEY" -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$YCYH_SSH_KNOWN_HOSTS" "${FIXTURE_USER}@127.0.0.1" true 2>/dev/null;then break;fi;sleep .1;done
ssh -T -p "$PORT" -i "$YCYH_SSH_KEY" -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$YCYH_SSH_KNOWN_HOSTS" -- "${FIXTURE_USER}@127.0.0.1" 'env YCYH_TARGET_IP=127.0.0.1 bash -s' <<'REMOTE'
test "$YCYH_TARGET_IP" = 127.0.0.1
printf 'fixture-ready\n'
REMOTE
cd "$ROOT"
mvn -B -ntp -pl oversight -am -Dtest=SshIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
