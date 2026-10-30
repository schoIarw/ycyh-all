# Common framework v1.0: functions are concatenated into one approved script.
set -Eeuo pipefail
umask 077
SEQ=0
ACTIVE_PHASE=STARTED
LOCK_DIR=''
STAGING=''
LOG_FILE=''
load_config() {
  command -v python3 >/dev/null || { echo 'python3 required' >&2; exit 10; }
  eval "$(python3 - '__CONFIG_BASE64__' <<'PYCONFIG'
import base64,json,os,shlex,sys
nodes=json.loads(base64.b64decode(sys.argv[1]))['nodes']
matched=[n for n in nodes if n['host_ip']==os.environ.get('YCYH_TARGET_IP')]
if len(matched)!=1: raise SystemExit('Target IP missing from approved configuration')
n=matched[0]
for key in ['task_id','token','callback_url','package_url','sha256','cluster_name','node_name','host_ip','expected_nodes','seed_hosts','master_nodes']:
    value=n[key]
    if isinstance(value,list):value=json.dumps(value)
    print(key.upper()+'='+shlex.quote(str(value)))
PYCONFIG
)"
  : "${TASK_ID:?}" "${TOKEN:?}" "${HOST_IP:?}"
}
log() {
  local text="$(date -u +%FT%TZ) [$ACTIVE_PHASE] $*"
  printf '%s\n' "$text"
  if [[ -n "$LOG_FILE" ]]; then printf '%s\n' "$text" >> "$LOG_FILE"; fi
}
report() {
  local phase="$1" message="$2" payload attempt
  SEQ=$((SEQ+1))
  payload=$(python3 - "$SEQ" "$phase" "$message" <<'PYREPORT'
import json,sys
print(json.dumps({'seq':int(sys.argv[1]),'phase':sys.argv[2],'message':sys.argv[3][:7000]}))
PYREPORT
)
  for attempt in 1 2 3; do
    if curl --fail --silent --show-error --connect-timeout 3 --max-time 8 \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      --data-binary "$payload" "$CALLBACK_URL" >/dev/null; then return 0; fi
    # Same sequence number on retries: callback receiver deduplicates.
  done
  return 20
}
cleanup() {
  if [[ -n "$STAGING" && -d "$STAGING" ]]; then rm -rf -- "$STAGING"; fi
  if [[ -n "$LOCK_DIR" ]]; then rmdir -- "$LOCK_DIR" 2>/dev/null || true; fi
}
on_error() {
  local code=$? line="$1" details=''
  trap - ERR
  set +e
  if [[ -n "$LOG_FILE" && -f "$LOG_FILE" ]]; then details=$(tail -c 4500 "$LOG_FILE"); fi
  report FAILED "stage=$ACTIVE_PHASE exit=$code line=$line; $details" || true
  log "Failed at line $line, exit=$code; existing installation/processes preserved for diagnosis"
  exit "$code"
}
run_stage() {
  ACTIVE_PHASE="$1"
  log "Starting $1"
  report PROGRESS "开始阶段：$1"
  "$2"
  log "Completed $1"
}
framework_main() {
  load_config
  trap 'on_error "$LINENO"' ERR
  trap cleanup EXIT
  command -v curl >/dev/null
  report STARTED "执行已审批脚本 task=$TASK_ID host=$HOST_IP"
  run_stage PREFLIGHT component_preflight
  run_stage DOWNLOAD component_download
  run_stage INSTALL component_install
  report INSTALLED "安装与配置完成：$INSTALL_DIR"
  run_stage START component_start
  report VERIFYING '等待所有预期节点加入集群'
  run_stage VERIFY component_verify
  report SUCCEEDED "ES集群=$CLUSTER_NAME 节点=$NODE_NAME 组网验证通过；已移除首次引导配置"
  log 'Deployment succeeded'
}
