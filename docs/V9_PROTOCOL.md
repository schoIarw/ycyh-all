# V9 脚本服务化接口对齐说明

依据用户提供的《v9脚本服务化http接口文档20261016(1).docx》，将 oversight 的默认外部协议改为 V9，clustermgr 同步使用 V9 客户端。原始文档保持不变，示例中的人员、电话号码、token、业务IP和脚本内容没有复制到源码。文档内容指纹：

`1a85113d3cd8ef9d00a5efdb8e0756498a9c606d92ba0187ce621e02f32c96ae`

文档正文加嵌入截图共识别22项接口（包含实时输出路径）。`startCommonTask` 的返回体依据原文内嵌截图读取，不将正文中未提取到的图片误当作接口未定义。接口清单与响应字段/类型骨架保存在 `oversight/src/test/resources/v9-contracts.json`，由原文提取并删除示例值，用于回归检查。

## 接口清单

以下是文档测试环境使用的 `/api` 前缀。正文不带 `/api` 的路径同时提供别名；Spring MVC兼容末尾 `/`。GET实时输出另兼容文档短路径 `/taskExecute/getRealTimeOutPutMessage`。

| 序号 | 方法 | 路径 |
|---:|---|---|
| 1 | GET | `/api/system/authority/getSecretToken` |
| 2 | POST | `/api/scriptApi/groups/queryAgentInfoGroupRole` |
| 3 | POST | `/api/scriptApi/taskApply/scriptExecAuditing` |
| 4 | POST | `/api/scriptApi/taskApply/listTaskApply` |
| 5 | POST | `/api/scriptApi/taskTemplate/listCloneTask` |
| 6 | POST | `/api/scriptApi/taskExecute/listCompleteScriptTasks` |
| 7 | POST | `/api/scriptApi/runtime/listTaskRuntime` |
| 8 | GET | `/api/scriptApi/runtime/getOutPutMessage` |
| 9 | POST | `/api/scriptApi/taskTemplate/getScriptTemplateDetail` |
| 10 | POST | `/api/scriptApi/taskTemplate/execAuditTemplateTask` |
| 11 | POST | `/api/scriptApi/taskExecute/listRunningScriptTasks` |
| 12 | POST | `/api/scriptApi/category/selectCategoryListDFS` |
| 13 | POST | `/api/scriptApi/myScript/getScriptDetail` |
| 14 | POST | `/api/scriptApi/taskTemplate/queryTemplateGroupInfo` |
| 15 | POST | `/api/scriptApi/taskTemplate/queryTemplateAgentInfo` |
| 16 | POST | `/api/scriptApi/taskExecute/stopScriptInstanceShell` |
| 17 | POST | `/api/approvalApi/api/operation` |
| 18 | POST | `/api/scriptApi/taskExecute/scriptTaskStart` |
| 19 | POST | `/api/scriptApi/taskExecute/listTaskReadyToExecute` |
| 20 | POST | `/api/scriptApi/info/getTaskBindAgentInfo` |
| 21 | POST | `/api/scriptApi/taskTemplate/startCommonTask` |
| 22 | GET | `/api/scriptApi/taskExecute/getRealTimeOutPutMessage` |

所有POST使用JSON，所有脚本/审批接口使用消息头 `secretToken`。获取token只需GET查询参数 `username`，不额外要求旧版 `X-API-Key`。成功统一为HTTP200，业务失败为HTTP200加 `code="10602"`；成功 `code="10000"`，始终保留 `code/message/data` 三个字段，包括值为null的字段。

获取token保留原文拼写 `data.exipreTime`，格式 `yyyyMMddHHmmss`；没有擅自改成 `expireTime`。`data.user` 使用原文的字段名；模拟用户元数据采用配置值或null，不伪造示例中的真实人员身份。token随机生成，数据库只存SHA-256和到期时间。

## 关键编号不可混用

| 编号 | 来自何处 | 使用场景 |
|---|---|---|
| scriptInfoId / uniqueUuid | 脚本清单 | 脚本组信息 |
| scriptInfoVersionId | 脚本清单；详情的 scriptVersionDto.id | 任务申请使用的脚本版本 |
| srcScriptUuid | 脚本版本 | 查询详情、运行记录关联 |
| taskId | 任务申请响应 | 查询任务；启动时字段名为scriptTaskId |
| auditId | 任务申请响应 | 审批请求的taskId字段（这里传审批ID） |
| taskIpsId | getTaskBindAgentInfo | scriptTaskStart.taskIps数组 |
| scriptTaskInstanceId | listRunningScriptTasks | 查询本次运行实例 |
| runtime.id | listTaskRuntime | 输出查询id、终止请求runTimeIds |

所有ID以JSON整数输出，保持Java Long精度；DTO与客户端用Long/JsonNode读取，页面由服务端直接输出完整十进制字符串。不得在JavaScript中先把超过2^53的ID转换成Number再回传。

## 执行语义

1. 获取token，查询Agent和脚本版本/参数。
2. 提交 `scriptExecAuditing`。白名单 `level=0`：响应auditId为null，触发类任务直接调度；风险等级1/2/3：保存固定脚本、参数、附件和主机快照，生成auditId，等待审批。
3. 风险脚本由指定审批人调用 `/api/approvalApi/api/operation`，`approvalState=1`通过，2驳回；驳回必须填写意见。传任务taskId代替auditId、申请人冒充审批人、重复审批均失败。
4. 审批通过**不自动执行**。客户端查询待执行任务和绑定Agent，使用真实taskIpsId调用 `scriptTaskStart`。clustermgr 会自动执行这个“批准后的启动”步骤，但不会替用户审批。
5. 运行后根据任务详情获取instanceId，再查询Agent运行实例。状态为10运行、20完成、30异常、60终止。读取标准输出或实时输出时使用runtime.id。
6. 终止请求支持runTimeIds或agentAddress选定实例，taskId或taskInstanceId界定任务范围。校验所属任务和调用身份后，通过SSH终止该运行实例的远端进程组；不靠修改数据库状态伪装终止。

白名单与风险流程依据本次V9文档，取代原先“所有脚本一律审批、批准即执行”的0.1原型约定。多台主机仍是一份任务、一份审批，不为安装的每个阶段新建申请。一个任务的execuser是单值，clustermgr拒绝将不同执行用户的主机混在同一任务。

驱动模式：1只执行所选绑定主机；2分批，某批失败后不再执行剩余批次；3分批且继续后续批次；4串行队列。eachNum接受文档的0—200，0按单并发执行；本机SSH执行池上限8，不对外伪报实际并发能力。

调度：taskScheduler=0触发，2按taskTime毫秒时间戳定时，1按Quartz cron周期执行，支持原文七字段 `0 0 0 * * ? *`。超时单位为秒，null/省略/-1/0不设置执行超时。风险定时/周期任务也必须先审批，再通过启动接口进入调度状态；终止接口只针对运行实例。

## 模拟平台的资源登记

原文没有提供脚本上传、分类/Agent/资源组创建或常用任务保存接口。因此不编造V9上传路径。管理员登录 oversight，在“脚本与资源登记”页面导入目录JSON；此页面属于模拟器管理功能，使用登录会话和CSRF，不是新增的对接协议。

启动时自动登记：

- 配置白名单中的Agent、默认资源组、通用分类。
- `ycyh_es_install` 风险等级3的SHELL脚本版本，适用Linux。
- 对应常用任务模板，参数名 `configBase64`，paramOrder=1，类型String。

clustermgr 查询已登记版本，将部署配置作为 `params[].paramDefaultValue` 传入。完整脚本提前组装在该版本中；主机执行期间不再从其他地址下载执行脚本。安装包仍从warehouse下载并核验SHA-256。

目录导入示例（ID省略时自动生成；脚本详情使用文档的数据结构）：

```json
{
  "scripts": [{
    "scriptName": "example_install",
    "scriptNameZh": "安装示例",
    "scriptType": "sh",
    "execuser": "es",
    "scriptVersionDto": {
      "level": 3,
      "version": "1.0",
      "expectType": 2,
      "expectLastline": "0",
      "timeout": -1,
      "scriptContentDto": {"content": "#!/usr/bin/env bash\nset -Eeuo pipefail\nprintf '%s\\n' \"$1\"\n"},
      "parameterValidationDtoList": [{"paramName":"message","paramOrder":1,"paramType":"String","paramDefaultValue":"hello"}],
      "attachmentUploadDtoList": []
    }
  }]
}
```

可同时提供 `categories`、`agents`、`groups`、`templates` 数组。分类用id/parentId/code/name；Agent用sysmAgentInfoId/agentIp/agentPort/execUserName；资源组用sysmComputerGroupId/cpName/agentIds；常用任务用id/taskName/scriptInfoVersionId/chosedAgentUsers或chosedResGroups/resGroupFlag/params/taskInfo。Agent登记仍受服务端SSH白名单约束，不能通过导入绕过主机或用户名限制。

附件由脚本版本的attachmentUploadDtoList预登记（name、id或response.id、size、contents Base64），任务按scriptTempAttachments中的id选取。执行前验证附件、文件名和大小，写入本实例私有工作目录；不将附件拼接为命令。不存在的附件或没有本地内容的附件返回失败。

## 文档差异与未定义项处理

| 原文情况 | 实现与对接要求 |
|---|---|
| 正文URL与测试URL是否带/api不一致 | 同时提供两种前缀，客户端默认测试环境形式 |
| listTaskRuntime重复出现 | 同一个接口，支持文档中的分页、IP、时间、状态和instanceId过滤 |
| token过期字段拼成exipreTime | 原样保留，未纠正字段名 |
| taskInfo.startType与外层startType的说明/例子互有错位 | 保留两个层次的输入，不在客户端擅自互换；默认请求按示例发送 |
| scriptTempAttachments.isTempFlag在两节中含义相反 | 保留原值，附件根据已登记的ID解析，不据相反说明猜测另一套内容来源 |
| 附录有taskIps填taskId的例子 | 按正式参数表使用绑定表ID，错误ID拒绝执行，避免误操作 |
| 终止附录有taskId与instanceId相同 | 兼容可确认属于该任务的实例ID；仍校验范围，不能跨任务终止 |
| startCommonTask正文返回结果在图片中 | 已读取原图，实现code/message及data.auditId/taskId；白名单auditId=null |
| auditType/state等审批展示字段未定义枚举，未提供独立审批结果查询接口 | 模拟器用auditType 0待审批、1通过、2驳回。clustermgr默认把2映射为驳回；连接真实平台前必须确认，或设置OVERSIGHT_REJECTED_AUDIT_TYPE=-1禁用此推断。未明确时待执行列表为空不能证明“已驳回”。 |
| 用户目录、认证签名算法和生产SSO流程未提供 | 模拟器只为配置的调用用户与审批用户按username换token；不能将该模拟身份机制当作生产SSO实现 |

协议字段、路径与返回结构来自原文；未定义的身份、审批枚举、资源管理、并发实现和执行器行为在此明确记录。未连接客户平台，不能把模拟器合同测试描述为客户平台互通认证。

## 配置与升级

oversight新增变量：OVERSIGHT_OPERATOR（默认operator）、OVERSIGHT_OPERATOR_ID（默认1）、OVERSIGHT_REVIEWER_ID（默认2）、OVERSIGHT_TOKEN_TTL_SECONDS（默认7200）。审批登录名仍为OVERSIGHT_ADMIN_USER；执行用户仍由SSH_ALLOWED_USERS指定。

clustermgr新增变量：OVERSIGHT_USERNAME、OVERSIGHT_AUDITOR_ID、OVERSIGHT_AUDITOR_NAME、OVERSIGHT_SCRIPT_NAME（默认ycyh_es_install）、OVERSIGHT_REJECTED_AUDIT_TYPE。两端的审批用户ID/名称需一致；连接真实平台时从客户提供的目录填入，不复制文档示例中的个人信息。

V9存储新增v9_objects/v9_chunks/v9_counter/v9_tokens，不删除旧表。JSON分块避免MySQL TEXT 64KiB限制，审批快照和执行输出也可跨块保存。旧 `/api/v1` 接口默认关闭，设置OVERSIGHT_LEGACY_ENABLED=true可临时恢复旧接口及旧界面供历史核查；clustermgr已经切到V9，不依赖旧接口。

升级前应完成或核查旧版未结束的申请；不将旧UUID申请冒充V9数值taskId，不自动重放。已登记V9脚本不会因应用重启而自动覆盖；更新安装框架后应经管理员登记新版本并确认clustermgr选择的版本。

## 验证

- V9ProtocolTest：22接口响应骨架、int64精度、token/别名、白名单/风险、审批与启动分离、错误ID、驳回、快照、资源组、模板、参数、调度、输出、终止。
- V9RemoteSystemsTest：客户端只发送文档定义路径/字段，保留长整型ID，启动用绑定ID，按实例状态映射结果。
- SshIntegrationTest / V9HttpSshTest：真实OpenSSH、字面参数、stdout/stderr、实时输出、超时、进程组终止、真实V9 HTTP批准—启动—结果闭环。
- tests/http-smoke.py：真实数据库及三服务联调，额外验证超过64KiB的脚本目录登记，审批驳回不安装ES。
- tests/framework-test.py：脚本阶段闭环与归档检查。真实ES二进制实装仍需在目标环境验收。
