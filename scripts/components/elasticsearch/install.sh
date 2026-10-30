# Elasticsearch adapter v1.0. PoC: ES 7.17 / 8.x Linux archives, no TLS/security.
component_preflight() {
  [[ "$(id -u)" -ne 0 ]] || { log 'Elasticsearch must not run as root'; return 11; }
  [[ "$(uname -s)" == Linux ]]
  for tool in curl python3 sha256sum nohup sed; do command -v "$tool" >/dev/null; done
  [[ -r /proc/sys/vm/max_map_count ]] && [[ "$(cat /proc/sys/vm/max_map_count)" -ge 262144 ]] || { log 'Administrator must set vm.max_map_count >= 262144'; return 12; }
  [[ "$(ulimit -n)" == unlimited || "$(ulimit -n)" -ge 65535 ]] || { log 'nofile must be >=65535'; return 12; }
  BASE_DIR="$HOME/ycyh-es"
  mkdir -p -- "$BASE_DIR"
  # A single ES instance per host; shared 9200/9300, lock covers every cluster.
  mkdir -- "$BASE_DIR/.deployment-lock" || { log 'Another deployment holds host lock'; return 13; }
  LOCK_DIR="$BASE_DIR/.deployment-lock"
  INSTALL_DIR="$BASE_DIR/$CLUSTER_NAME/$NODE_NAME"
  [[ ! -e "$INSTALL_DIR" && ! -L "$INSTALL_DIR" ]] || { log 'Existing installation found; refusing overwrite/retry'; return 14; }
  mkdir -p -- "$BASE_DIR/$CLUSTER_NAME"
  LOG_FILE="$BASE_DIR/$CLUSTER_NAME/$TASK_ID.log"
  python3 - "$HOST_IP" <<'PYPORT'
import socket,sys
sockets=[]
for port in (9200,9300):
    s=socket.socket();s.bind((sys.argv[1],port));sockets.append(s)
for s in sockets:s.close()
PYPORT
  STAGING=$(mktemp -d "$BASE_DIR/.stage.XXXXXXXX")
  log 'Preflight passed; host ports, limits, directory and lock verified'
}
component_download() {
  curl --fail --silent --show-error --connect-timeout 10 --max-time 240 \
    --max-filesize 4294967296 --output "$STAGING/package.tar.gz" "$PACKAGE_URL"
  printf '%s  %s\n' "$SHA256" "$STAGING/package.tar.gz" | sha256sum -c - >> "$LOG_FILE"
  python3 - "$STAGING" <<'PYDISK'
import os,shutil,sys
p=sys.argv[1]
if shutil.disk_usage(p).free < os.path.getsize(p+'/package.tar.gz')*4+1024**3:raise SystemExit('Insufficient disk space')
PYDISK
  log 'Archive SHA-256 verified'
}
component_install() {
  python3 - "$STAGING" <<'PYTAR' >> "$LOG_FILE" 2>&1
import os,sys,tarfile,posixpath,re
base=sys.argv[1]
with tarfile.open(base+'/package.tar.gz','r:gz') as t:
    members=t.getmembers()
    if not members or len(members)>300000 or sum(m.size for m in members)>20*1024**3:raise ValueError('Archive bounds exceeded')
    names=set();links=set()
    for m in members:
        n=posixpath.normpath(m.name)
        if m.name.startswith('/') or n=='..' or n.startswith('../') or not (m.isfile() or m.isdir() or m.issym() or m.islnk()):raise ValueError('Unsafe archive member')
        names.add(n.split('/')[0])
        if m.issym() or m.islnk():links.add(n)
    if len(names)!=1:raise ValueError('Expected exactly one ES root directory')
    top=next(iter(names))
    if not re.fullmatch(r'elasticsearch-(7[.]17[.][0-9]+|8[.][0-9]+[.][0-9]+)',top):raise ValueError('Only Elasticsearch 7.17 / 8.x release archives supported')
    for m in members:
        n=posixpath.normpath(m.name)
        if any('/'.join(n.split('/')[:i]) in links for i in range(1,len(n.split('/')))):raise ValueError('Member under link')
        if m.issym() or m.islnk():
            target=posixpath.normpath(posixpath.join(posixpath.dirname(n),m.linkname) if m.issym() else m.linkname)
            if m.linkname.startswith('/') or not (target==top or target.startswith(top+'/')):raise ValueError('Link escapes archive root')
    # All paths/link targets validated; destination is fresh, private, not attacker controlled.
    t.extractall(base+'/extract')
with open(base+'/root','w') as f:f.write(top)
PYTAR
  local extracted="$STAGING/extract/$(cat "$STAGING/root")"
  [[ -x "$extracted/bin/elasticsearch" && -x "$extracted/jdk/bin/java" ]]
  "$extracted/jdk/bin/java" -version >> "$LOG_FILE" 2>&1
  mv -- "$extracted" "$INSTALL_DIR"
  mkdir -p -- "$INSTALL_DIR/data" "$INSTALL_DIR/logs" "$INSTALL_DIR/config/jvm.options.d"
  cat > "$INSTALL_DIR/config/elasticsearch.yml" <<YAML
cluster.name: "$CLUSTER_NAME"
node.name: "$NODE_NAME"
network.host: "$HOST_IP"
http.port: 9200
transport.port: 9300
discovery.seed_hosts: $SEED_HOSTS
cluster.initial_master_nodes: $MASTER_NODES
xpack.security.enabled: false
YAML
  if [[ "$(basename "$INSTALL_DIR" 2>/dev/null)" != '' ]]; then
    printf '%s\n' '-Xms512m' '-Xmx512m' > "$INSTALL_DIR/config/jvm.options.d/heap.options"
  fi
  log "Installed to $INSTALL_DIR; security disabled by explicit PoC opt-in"
}
component_start() {
  nohup "$INSTALL_DIR/bin/elasticsearch" -d -p "$INSTALL_DIR/es.pid" >> "$LOG_FILE" 2>&1 < /dev/null
  log 'Start command returned; waiting for cluster health'
}
component_verify() {
  local attempt response
  for attempt in $(seq 1 90); do
    if response=$(curl --fail --silent --show-error --connect-timeout 2 --max-time 3 \
      "http://$HOST_IP:9200/_cluster/health?wait_for_status=yellow&wait_for_nodes=$EXPECTED_NODES&timeout=1s" 2>/dev/null); then
      if python3 - "$CLUSTER_NAME" "$EXPECTED_NODES" "$response" <<'PYHEALTH'
import json,sys
j=json.loads(sys.argv[3])
if j.get('cluster_name')!=sys.argv[1] or j.get('status') not in ('green','yellow') or j.get('number_of_nodes')!=int(sys.argv[2]) or j.get('timed_out',False):raise SystemExit(1)
PYHEALTH
      then
        sed -i '/^cluster.initial_master_nodes:/d' "$INSTALL_DIR/config/elasticsearch.yml"
        log "Health passed: $response"
        return 0
      fi
    fi
    sleep 2
  done
  log 'Cluster formation/health verification timed out'
  return 30
}
