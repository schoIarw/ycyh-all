# YCYH HTTP接口

## Oversight V9

默认对接协议已改为用户提供的V9文档。22项接口、字段、编号关系、流程和原文差异见 [V9_PROTOCOL.md](V9_PROTOCOL.md)，机器可读定义见 [v9-openapi.json](v9-openapi.json)。原先 `/api/v1/scripts`、`/api/v1/deployments`、`X-API-Key`、UUID申请和纯文本结果不是V9接口，默认关闭。

```bash
# 先获取token，返回data.exipreTime/user/token
curl --fail --get 'http://127.0.0.1:8081/api/system/authority/getSecretToken' \
  --data-urlencode 'username=operator'

# TOKEN填写上一步data.token；不要从旧版API_KEY代替
curl --fail -H "secretToken: $TOKEN" -H 'Content-Type: application/json' \
  --data '{"scriptName":"ycyh_es_install"}' \
  http://127.0.0.1:8081/api/scriptApi/myScript/getScriptDetail
```

风险任务顺序：查询脚本/Agent → scriptExecAuditing → 人工审核operation（taskId字段传auditId）→ listTaskReadyToExecute → getTaskBindAgentInfo → scriptTaskStart（taskIps传taskIpsId）→ listRunningScriptTasks → listTaskRuntime → getOutPutMessage。

响应均为JSON `{code,message,data}`，业务成功10000、失败10602。审批通过与启动是两步；白名单脚本申请后自动执行。

## Warehouse

| 方法/路径 | 行为 |
|---|---|
| GET /目录/ | HTML清单 |
| GET /文件 | 二进制下载 |
| HEAD /文件 | 文件长度 |
| GET /_catalog | 深度6、最多200个tar.gz的名称、相对路径、大小、SHA-256 |

只读、目录路径检查、符号链接限制保持原有实现，不增加V9无关接口。

## Clustermgr

以下接口仍使用管理页面登录会话，写入需要CSRF。

| 接口 | 功能 |
|---|---|
| GET /api/me | 用户 |
| GET /api/hosts | 配置的主机及执行用户 |
| GET /api/packages | 仓库安装包 |
| GET /api/clusters | 集群和逐主机状态、V9 taskId与审批链接 |
| POST /api/clusters | 提交一份基于已登记脚本版本的部署任务 |
| GET /api/clusters/{id}/events | 独立安装详情回传 |

```json
{"name":"es-demo01","packagePath":"elasticsearch-8.17.0-linux-x86_64.tar.gz","hostIps":["192.168.10.11","192.168.10.12"]}
```

同一任务主机的执行用户必须相同。管理系统不向主机直连、不代替审批人批准，仅在V9平台返回可执行任务后调用启动接口。

## 安装回传

这是本项目的独立信息通道，不属于V9管控协议，不改变客户V9接口。这里的taskId是clustermgr部署子任务UUID，不是V9数值taskId；脚本配置已带入正确UUID及回传凭据。

`POST /api/callbacks/{taskId}`，消息头 `Authorization: Bearer 本任务token`：

```json
{"seq":1,"phase":"STARTED","message":"安装开始"}
```

phase：STARTED / PROGRESS / INSTALLED / VERIFYING / SUCCEEDED / FAILED；seq正整数递增，重试使用同序号。单消息最多8000字符，框架截断到7000。重复旧序号不重复写入；终态不允许被后续阶段覆盖。最终成功仍需V9运行实例完成和安装验证回传同时成立。
