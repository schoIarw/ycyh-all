# HTTP接口与审批协议

所有 JSON 使用 UTF-8。oversight 默认8081、warehouse 8082、clustermgr 8080。

## 一、oversight 管控接口

所有 `/api/v1/**` 接口要求 `X-API-Key`，包括只读状态查询。**登记及执行申请都不会在提交时执行。** 审批页面使用独立登录会话与 CSRF；没有 API key 直接批准接口。

### 1. 上传脚本并申请登记

`POST /api/v1/scripts`，multipart/form-data：

| 字段 | 必填 | 说明 |
|---|---|---|
| file | 是 | 非空 UTF-8 SHELL文本，最大1 MiB（含multipart请求总量限制） |
| type | 否 | 默认 SHELL，目前仅支持 SHELL |

```bash
curl -sS -H "X-API-Key: $OVERSIGHT_API_KEY" \
  -F 'type=SHELL' -F 'file=@scripts/example.sh' \
  http://127.0.0.1:8081/api/v1/scripts
```

返回 HTTP202（以下 UUID 为格式示例）：

```json
{
  "applicationId":"f07b3608-cf08-446f-aab6-26b166276279",
  "uuid":"3ea109d0-b40d-43bc-b90c-402aa85c9605",
  "kind":"UPLOAD",
  "status":"PENDING",
  "result":null,
  "results":{},
  "targets":[],
  "sha256":"脚本内容的SHA256"
}
```

实际响应还包含创建/审批/完成时间、审批人和意见。UUID在提交时预分配，**只有批准后**内容、SHELL类型、UUID才进入可执行脚本表。批准后的登记结果1、驳回0。

### 2. 单主机执行申请（保持三个业务参数）

`POST /api/v1/scripts/execute`，application/json：

```bash
curl -sS -H "X-API-Key: $OVERSIGHT_API_KEY" \
  -H 'Content-Type: application/json' \
  --data '{"scriptUuid":"已审批登记的脚本UUID","hostIp":"192.168.10.11","username":"es"}' \
  http://127.0.0.1:8081/api/v1/scripts/execute
```

参数仅 `scriptUuid`、`hostIp`、`username`。IP/用户名必须在允许清单，脚本须已登记。返回202待审批申请；批准后以指定用户 SSH 到指定主机，将数据库中的脚本从 stdin 交给 Bash。SSH目标地址、端口、私钥和known_hosts由服务端配置，客户端不能传递SSH选项。

这不是“上传一次即可任意执行”的授权：每次执行仍产生新的审批申请。执行退出码0 → 1；其他执行失败 → 0。最终结果通过下述结果接口读取。

### 3. 合并部署申请（clustermgr 默认使用）

`POST /api/v1/deployments`，multipart/form-data：

- `file`：待登记的一份完整脚本，含已准备的组件安装模板和本次部署配置。
- `targets`：JSON数组，1—8个不同IP的明确执行目标。

```bash
curl -sS -H "X-API-Key: $OVERSIGHT_API_KEY" \
  -F 'file=@approved-deployment.sh' \
  -F 'targets=[{"hostIp":"192.168.10.11","username":"es"},{"hostIp":"192.168.10.12","username":"es"}]' \
  http://127.0.0.1:8081/api/v1/deployments
```

返回202，kind=DEPLOYMENT。**同一申请一次审批覆盖登记与全部目标执行。** 批准后登记一次UUID并在每台主机上执行同一内容；oversight受控注入 `YCYH_TARGET_IP=本次目标IP`，脚本据此选择自己的配置。脚本应实现完整检查、安装、验证，不能在执行过程中再请求其他脚本执行申请。

### 4. 查询申请

`GET /api/v1/applications/{applicationId}`，返回状态与结果，例如：

```json
{
  "applicationId":"申请ID",
  "uuid":"脚本UUID",
  "kind":"DEPLOYMENT",
  "status":"FAILED",
  "result":0,
  "results":{"192.168.10.11@es":1,"192.168.10.12@es":0},
  "targets":[{"hostIp":"192.168.10.11","username":"es"},{"hostIp":"192.168.10.12","username":"es"}]
}
```

| 状态 | 含义 | result |
|---|---|---|
| PENDING | 未审批，未执行 | null |
| APPROVED | 已批准，待执行 | null |
| RUNNING | 执行中 | null |
| SUCCEEDED | 上传登记成功，或全部执行退出0 | 1 |
| FAILED | 部分执行失败/超时，或执行队列满 | 0 |
| REJECTED | 审批驳回，未执行 | 0 |
| UNKNOWN | 服务/执行中断，真实状态待核查 | null |

`GET /api/v1/applications/{applicationId}/result`：终态有结果时 HTTP200 + `text/plain` `1`/`0`；尚无明确结果时HTTP202 + `PENDING`。UNKNOWN也没有明确结果，需要人工核查，不要无限当作正常排队。

### 5. 审批页面路由

| 方法/路径 | 功能 |
|---|---|
| GET /login、POST /login | 页面登录 |
| GET / | 最近200条申请 |
| GET /approvals/{id} | 内容、哈希、主机清单、审批与结果详情 |
| POST /approvals/{id}/approve | 当前登录审批人批准（CSRF必需） |
| POST /approvals/{id}/reject | 当前登录审批人驳回（CSRF必需） |

决策参数 `reason`（可选，最多保存500字符）；同一申请重复审批返回409。这里只允许PENDING申请决策，不会重复触发执行。脚本快照不提供更新接口。

## 二、warehouse 接口

| 接口 | 说明 |
|---|---|
| GET /目录/ | HTML目录清单，支持中文和HTML转义 |
| GET /文件 | 二进制下载 |
| HEAD /文件 | 获取长度，不发送正文 |
| GET /_catalog | 深度≤6，最多200个 tar.gz 包的名称、URL相对路径、大小、SHA-256 |

```json
[{"name":"es/elasticsearch-8.17.0-linux-x86_64.tar.gz","path":"es/elasticsearch-8.17.0-linux-x86_64.tar.gz","sha256":"64位十六进制","size":123456}]
```

仓库根目录规范化后锁定；拒绝 `..` 和指向根外的符号链接，目录列表跳过符号链接。仅只读GET/HEAD，无上传、删除接口，无账户体系。`/_catalog`为保留路径，后台每次查询均计算SHA，包应由受信任人员维护。

## 三、clustermgr接口

除回传接口和登录/样式页面外均需登录会话；JSON写入要求页面中的CSRF token。

| 接口 | 功能 |
|---|---|
| GET /api/me | 登录用户名 |
| GET /api/hosts | 可选主机IP与执行用户 |
| GET /api/packages | 获取仓库组件目录 |
| GET /api/clusters | 集群、申请ID、审批链接、逐主机任务状态 |
| POST /api/clusters | 创建一次完整部署申请 |
| GET /api/clusters/{id}/events | 本集群安装阶段及错误回传 |

创建请求：

```json
{"name":"es-demo01","packagePath":"elasticsearch-8.17.0-linux-x86_64.tar.gz","hostIps":["192.168.10.11","192.168.10.12"]}
```

返回 `{"id":"集群UUID"}`。同名集群拒绝重复创建；一份申请最多8台不同主机；软件包路径必须来自当前仓库目录，不能任意填URL。任务入库后异步提交oversight；不能把创建请求成功解释为ES已经安装。

## 四、安装信息回传

`POST /api/callbacks/{taskId}`，以任务token鉴权，无需浏览器登录和CSRF：

```bash
curl --fail -H 'Authorization: Bearer 本任务token' \
  -H 'Content-Type: application/json' \
  --data '{"seq":1,"phase":"STARTED","message":"已开始安装"}' \
  http://管理服务IP:8080/api/callbacks/任务ID
```

| 字段 | 规则 |
|---|---|
| seq | 正整数，本任务内单调递增；网络重试用同一个序号 |
| phase | STARTED / PROGRESS / INSTALLED / VERIFYING / SUCCEEDED / FAILED |
| message | 非null，最多8000字符；公共框架自动限制到7000 |

成功返回 `{"accepted":true}`。token只在模板配置/审批脚本中使用；clustermgr保存其SHA-256，不在列表输出原文。重复或较旧seq不重复写入；更高seq试图覆盖最终阶段返回409；已完成、驳回或尚未提交的任务拒绝新的事件。UNKNOWN任务可补收日志供核查，但不会自动把不确定的执行结果变成成功。

回传接口不会启动任何脚本。脚本进程通过stdin收到配置，执行日志不包含token；最终成功回传不能代替管控执行退出结果。

## 五、HTTP错误与重试

未授权API401；未登录页面跳转login；缺少CSRF403；不存在404；不合法输入400；重复审批409。服务依赖问题返回相应5xx，管理界面显示错误。

POST提交超时后不要自动重新提交（原申请可能已经入库）。先用审批清单中 `install-es-集群ID.sh` 核查。GET状态查询可重试，不产生执行；回传可按同序号重试。当前未实现客户端幂等键/分布式exactly-once，已通过不可重复审批和不自动重放降低重复执行风险。
