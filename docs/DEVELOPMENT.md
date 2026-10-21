# 小店账本 Android

原生 Kotlin、Jetpack Compose / Material 3、Room、WorkManager、Jakarta Mail。Android 8.0（API 26）以上可用，无登录、服务器和云端数据库。项目没有预置员工、任务或演示账目。

## 安装与使用

构建后的安装包：`app/build/outputs/apk/debug/app-debug.apk`。传到手机，允许该文件来源安装，点击安装即可。后续更新使用同一应用 ID 和同一签名覆盖安装，**不要先卸载**；卸载会删除本地账本和本机密钥。正式长期分发时应使用自己妥善保管的发布签名，Android Studio 的 Generate Signed Bundle / APK 可以生成发布 APK。

1. 设置 → 员工管理：添加员工；支持重名、改名、停用和恢复使用。
2. 设置 → 任务管理：填写任务名称、每次员工工资及每次店铺收入。
3. 记账：选择工作日期，展开员工卡片，在任务旁输入次数（默认1），再点 **+**。例如输入2，保存两条独立记录；一次最多999次。备注选填，成功后收起键盘、清空备注并重置次数。
4. 卡片默认收起，显示员工姓名、当日次数及工资，方便同时看到更多员工。点“当天明细”查看紧凑列表，可修改或删除未结账记录。“历史员工”可查停用员工。
5. 工资结算 → **接着上次结账**：选择截止日期，默认昨天，最多算到昨天23:59:59.999。可提前保存工资单，不包括今天和未来账目。首次从首次记账起算，以后从上次截止时间之后接着算。**只查金额**：自由选择起止日期，包含首尾整天，查看工资和营业额，不生成工资单。
6. 设置 → 每月自动结账：设置每月几号、几点出单及发送报告，例如15日12:00；自动单只算到14日结束。提前结账不会改变固定日期，自动结账继续接上次截止点。遇月末不足用该月最后一天。
7. 已结账日期仍可补记：确认后原工资单、员工合计及营业额立即更新，保留修订时间、原因和前后合计；原单版本递增，排队发送修正版，补记不进入下一期工资。已列入工资单的原记录禁止直接改删，需先撤销最近一期。已发邮件无法收回。
8. 设置 → 邮箱与备份：填写邮箱及授权码，接收邮箱留空则发给自己。常见服务自动配置，授权码获取帮助内附QQ、163/126、Gmail步骤和官方说明链接。完整账本备份需要另设至少6位的独立密码，纯数字可用；工资报告不需要备份密码。
9. “忘记密码 / 密保与找回”：可从题库自选三个不同的问题，三题全对可重设。原手机还可通过系统指纹/锁屏验证重设；恢复文件自动发给发件邮箱本人，无需记恢复码。换手机时导入账本和邮件中的 `.ledgerkey` 文件，或回答这份备份的三个问题。设置完成后重新导出新备份。详见 [RECOVERY.md](../RECOVERY.md)。

新账本默认启用每月自动结账。旧版升级保留原来的手动结账状态，避免升级后立即补办以往日期；店主在设置中核对安排并打开自动结账即可。开启后会逐期补办尚未生成的到期工资单。1.2.0使用用户提供的图片作为自适应启动图标，保留原业务数据及外观设置。

## 构建与测试

Android Studio 打开项目根目录，使用 **JDK 21**、Android SDK Platform 35、Build Tools 35.0.0。构建工具固定为 Gradle 8.11.1 / AGP 8.9.2 / Kotlin 2.1.20，Wrapper 带官方 SHA-256 校验。

```powershell
# JAVA_HOME 指向 JDK 21；local.properties 中配置 sdk.dir
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

本次环境另在 `.tools/` 内准备了 JDK、Gradle 和 SDK（不进入 Git）。可直接使用：

```powershell
$env:JAVA_HOME = (Resolve-Path '.tools/jdk/jdk-21.0.2').Path
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.tools/gradle-home'
& '.tools/gradle-8.11.1/bin/gradle.bat' :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

测试使用真实 Room SQLite（Robolectric），覆盖日期最后一毫秒、排除今天与未来、提前结账衔接、12点自动出单、漏跑月份补办、批量事务、并发防重、补记原单修订、旧数据库无损升级、旧版备份兼容和新修订单加密恢复。SMTP 测试检查实际传输、认证失败及证书校验；测试证书不进入应用 APK。Compose 测试验证折叠卡片、批量记账、区间查询、结账与补记确认。

真机测试请使用独立副本：`-PdeviceCheck=true` 会生成 `cn.shopledger.devicetest`（名称“小店账本·调试”），与正式账本隔离。各组分次运行；Gradle 会安装并清理测试副本：

```powershell
.\gradlew.bat -PdeviceCheck=true '-Pandroid.testInstrumentationRunnerArguments.class=cn.shopledger.AppFlowTest' :app:connectedDebugAndroidTest
.\gradlew.bat -PdeviceCheck=true '-Pandroid.testInstrumentationRunnerArguments.class=cn.shopledger.DeviceBackupTest' :app:connectedDebugAndroidTest
.\gradlew.bat -PdeviceCheck=true '-Pandroid.testInstrumentationRunnerArguments.class=cn.shopledger.DeviceRecoveryTest' :app:connectedDebugAndroidTest
.\gradlew.bat -PdeviceCheck=true '-Pandroid.testInstrumentationRunnerArguments.class=cn.shopledger.RecoveryUiTest' :app:connectedDebugAndroidTest
```

`ManualVerificationTest` 单独选择运行，需要机主在120秒内亲自在手机系统弹窗中完成指纹、人脸确认或锁屏密码验证，程序随后验证新密码导出与解密。没有机主配合时不要运行该组，也不要省略类名运行所有真机测试。

真机覆盖完整界面流程、Gmail授权码帮助、真实 Android Keystore、6位密码加密导出恢复、失败附件原样重试、原版及修正版工资邮件的实际传输，以及回到桌面后的真实 WorkManager / TLS SMTP 备份发送和北京时间同日去重。恢复测试验证密码、三个密保答案、邮箱恢复文件三种方式，错误输入不覆盖账本、失败限制、自动发给本人、重试与去重，以及题库选择和取消系统验证。测试不使用真实邮箱账号，信件只进入手机内的测试端点。

单元测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`；Lint 报告：`app/build/reports/lint-results-debug.html`。本次实际运行结果见 `VALIDATION.md`。

## 邮箱配置

唯一邮件通道为 **SMTP over SSL/TLS（隐式 TLS）**，一般使用端口 **465**。服务器必须具有系统信任且主机名匹配的证书；不支持明文 SMTP、STARTTLS / 587 或 OAuth API。

QQ/Foxmail、163、126、yeah.net、Gmail/Googlemail按邮箱后缀自动填写发信服务器及465端口；其他邮箱可展开高级设置。接收方可使用任意有效邮箱。Gmail发信需开启两步验证并生成16位应用专用密码；组织账号或高级保护账号可能不提供该选项，手机网络需能访问Google邮件服务。页面“授权码怎么获取”可按服务商分别查看步骤。保存后用测试发送确认连接；成功表示邮箱服务接受邮件，不能代替收件箱确认。

工资单报告与完整备份是两种邮件：固定结账时间生成工资单并排队发送文字报告，手动出单及补记修订后也排队发报告；完整加密备份按另设的每日时间发送。两者均有真实发送状态。工资报告含金额和员工姓名，以普通邮件正文发送；完整账本附件使用独立密码加密。

SMTP 授权码与自动备份所需的独立备份密码经 Android Keystore 的 AES-GCM 密钥加密后保存在本机，日志和备份文件均不包含它们。忘记备份密码时，可在原手机验证身份后重设，或使用已设置的三个密保问题、配套邮箱恢复文件找回。更改密码只影响以后生成的文件；旧封装版本1仍需原密码，不能补加恢复方式。

“立即备份并发送”加入 WorkManager 队列，设置页显示真实任务和持久化状态。失败附件保存在 App 内部目录，重试沿用原附件及 Message-ID；手动再次发送时优先重试现有待发送附件。SMTP 的“接受投递”与手机写入成功状态不能构成跨系统事务；极端断电可能重复投递，不能承诺 exactly-once。

自动计划按北京时间重新计算下一次单次任务，成功后继续安排下一天；断网等待、失败指数退避重试。系统休眠、省电、厂商后台限制会导致延迟，**不能保证准点**。正常移出最近任务不会清除 WorkManager；系统“强制停止”后需要重新打开 App。重启后的任务由 WorkManager 恢复。不需要精确闹钟和存储权限。

## 数据规则和备份格式

- 唯一账本数据库是 `shop-ledger.db`。业务设置与必要的发送状态也保存在 Room 中；WorkManager 使用其官方内部调度数据库。日统计直接聚合工作记录，不另存一份可写的金额缓存。
- 金额为 `Long` 整数分；单笔最多 10 亿元，记录最多 100 万条，避免 SQLite 累计溢出。名称 1–80 字符，备注最多 1000 字符。业务日期范围 1900–2199 年。
- 时间统一为 UTC epoch 毫秒，展示、分日、固定结算日和备份计划全部使用 `Asia/Shanghai`。历史记账使用所选日期 + 当前北京时间的时分秒；详情可精确修改。
- 任务名称、工资和收入保存快照；员工名称通过唯一 ID 读取当前姓名。停用不删除关联数据。
- 每次结账覆盖 `(上次截止时刻, 所选截止日最后一毫秒]`，仅处理尚未列入工资单的记录。截止日必须早于今天；未来和今天不会误入。自动结账固定在结账日前一天结束，延迟执行也不改变截止点，会逐期补办漏过的月份。结账是核算出单，不代表实际付款。
- 工资单通过工作记录的settlementId确定归属。补记落在已结账区间时，事务内分配给原单、重算分员工合计、递增版本、保存修订记录并清空本版发送状态；不会产生下一期待付。金额按补记时界面显示的任务单价保存快照。撤销最近一期时释放记录归属并删除相应明细、修订和发送状态；更早单保持不变。数据库版本1→2→3均有显式迁移，不会清空旧账；版本3仅新增设备恢复信息及尝试状态表。
- 唯一完整账本文件格式 `.ledgerbak`，业务JSON载荷仍为版本2。1.2.0生成加密封装版本2：`SLBK`魔数、32位封装版本2、32位JSON头长度、受认证的JSON头、12字节数据nonce、AES-256-GCM密文及标签。随机数据密钥由密码和随机恢复根密钥分别封装，三个密保答案可选地封装根密钥。PBKDF2-HMAC-SHA256固定600,000次，独立盐和nonce，单文件最大64 MiB，密码6–256位。旧封装版本1和旧业务载荷版本1继续可用原密码恢复。
- `.ledgerkey`只包含配套恢复编号和随机恢复根密钥，不含账目、备份密码或密保答案；它本身就是开锁凭据，不再要求另记密码，应保留在自己的邮箱。自动发送到发件邮箱本人，按编号及邮箱去重，网络失败重试。本机根密钥用Keystore保护。密保答案不明文存储；本机5次失败暂停5分钟，离线文件不能依靠App限制阻止外部猜测。
- 备份包含全部业务表、固定结算日、公共设置和背景 PNG；不包含设备密钥、授权码、备份密码或旧的发送执行状态。背景复制到内部目录，限制为 2048 像素、8 MiB；背景显示强度 0–40%，过浅的主题色会自动压暗以保证可读性。
- 恢复先校验加密完整性、ID、关系、金额、区间连续性、工作归属和修订金额，再事务替换全账本。失败保留原账本。恢复后关闭自动备份和自动结账，重新配置秘密信息；恢复的历史工资单不自动重发，之后补记会正常发送修正版。
- 系统云备份和设备搬家均明确排除账本及设备秘密，恢复统一使用加密的 `.ledgerbak`，配合原密码、这份文件生成时的三个密保答案或配套 `.ledgerkey`。

## 目录

`data/`：Room 实体、DAO、事务业务；`backup/`：唯一序列化与加密、Keystore、SMTP、Worker；`ui/`：Compose 页面；`Rules.kt`：金额和北京时间规则。单 Activity，无仓储接口、依赖注入框架或并行存储方案。
