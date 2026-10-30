# 验证说明与结果

验证日期：2026-10-30。结果针对交付源码与构建产物，不包含用户实际内网主机。

## 已完成

| 范围 | 实测方式 | 结果 |
|---|---|---|
| JDK11编译与运行 | Temurin 11.0.32.1，Maven3.9.9，release=11 | 三个子工程构建成功，可运行JAR已生成 |
| Java测试 | oversight审批/执行、SSH参数校验与超时；warehouse路径/下载；clustermgr权限/回传/单申请多主机 | 14个常规测试通过 |
| 真实SSH传输 | Java SshRunner → 系统OpenSSH客户端 → 本机用户态Paramiko SSH测试服务器；临时密钥与固定known_hosts | 执行成功返回1，exit42返回0，未知指纹被拒绝；1个额外集成测试通过 |
| 脚本框架 | 实际Bash执行公共框架与测试组件，接收真实HTTP回传；验证归档处理函数 | 5个测试通过：成功、失败码保留、合法归档配置、路径穿越拒绝、越界链接拒绝 |
| 真HTTP联调 | JDK11启动三个JAR；测试专用H2 PostgreSQL兼容模式；Python真实HTTP与cookie会话 | 登录、CSRF、批准脚本登记、单份部署申请、审批驳回向管理端传播通过 |
| 静态一致性 | bash -n、node --check、assemble.py --check | 通过 |

常规Maven运行会跳过需额外环境的 `SshIntegrationTest`；已使用独立用户态SSH测试环境单独运行该测试并通过。用户态fixture仅为测试工具，不是oversight的运行依赖；正式环境仍由系统OpenSSH连接真实SSH主机。

真HTTP测试只批准示例脚本登记，并驳回部署申请，确认驳回不执行；完整成功多主机流程由Java测试替代远端接口验证。未将用于拒绝测试的占位软件包作为真实ES软件包交付。

## 尚待目标环境完成

1. MySQL和PostgreSQL实际服务连通、建表、审批和部署状态写入。本环境无数据库守护进程/容器，当前数据库行为实测使用H2兼容模式；提供了两个数据库的初始化和连接配置，以及CI双数据库冒烟定义；后续远端实测以仓库Actions运行记录为准。
2. 选择目标Linux/CPU架构兼容的真实ES 7.17.x或8.x软件包，完成单机与多机实装、组网、查询、重启及错误注入。已验证模板逻辑和归档检查，不宣称完成真实ES二进制实装。
3. 真实网络中验证目标主机可访问仓库与回传地址，确认SSH权限、主机指纹、9200/9300互通、ulimit、vm.max_map_count、空间与OS兼容性。
4. 浏览器目视检查与操作验收。服务端模板渲染和真实HTTP已验证，未执行自动化浏览器像素/截图验收。

## 可复现命令

```bash
python3 scripts/assemble.py --check
bash -n clustermgr/src/main/resources/scripts/install-es.sh
node --check clustermgr/src/main/resources/static/app.js  # 有Node时执行，运行页面不需要Node
python3 tests/framework-test.py
mvn -B -ntp clean verify
# 具备sshd运行条件的普通Linux开发机
bash tests/ssh-integration.sh
# 或受限容器：仅测试环境额外安装paramiko
python3 -m pip install paramiko
python3 tests/ssh-userland.py
```

启动数据库与三个服务后，将测试所需账户、API key、服务地址导出为环境变量，运行 `python3 tests/http-smoke.py`。该测试产生一条已批准上传申请和一条被驳回部署申请，会保留审计数据，不安装ES。

## 内网验收建议

| 验收操作 | 预期 |
|---|---|
| 提交2台主机集群 | oversight只增加1条DEPLOYMENT待审批申请 |
| 不审批，等待至少2轮刷新 | 主机无安装，状态等待审批 |
| 驳回 | 管理界面显示审批驳回，远端无安装 |
| 新建另一集群，审批通过 | 两台主机并行安装并回传阶段 |
| 重复发送审批POST | 返回409，无第二次执行 |
| 替换为错误SHA/损坏包测试 | 下载校验或解包失败，无成功状态 |
| 占用端口/使用已有安装目录 | 前置检查失败，不覆盖已有安装 |
| 阻断9300使节点无法组成集群 | VERIFY超时，最终部署失败 |
| 阻断回传 | 无法将缺少最终验证信息的任务认定成功 |
| 执行中断开SSH或重启oversight | 不自动重放，核查远端已有进程/文件 |

ES验证成功允许yellow：默认副本数在单机情况下可能导致副本未分配。生产验收需另行要求green、分片数、容量、权限、TLS、备份和故障恢复。
