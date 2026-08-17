# LizzieYzy 重构进度清单（todo.md）

> 与《remake.md》配套的**进度看板**：记录已完成项、当前状态、待办与回归验收。
> 更新于 2026-08（Phase 0–2 已实施并编译打包验证；构建工具链：Maven 3.9.9，编译目标 JDK 17 字节码，运行验证环境 JDK 26）。

---

## ✅ 已完成（Phase 0–2）

### Phase 0：构建工程化（基建）
- [x] **pom 插件升级**：maven-compiler-plugin 3.13.0、maven-surefire-plugin 3.2.5（去掉 skip）、maven-shade-plugin 3.6.0
- [x] **.gitignore**：新增 target/、dependency-reduced-pom.xml、IDE/日志/运行时产物（config.txt、persist、jcef-bundle/、foxReq/、readboard/ 等）
- [x] **java.version 解析修复**（`Lizzie.java`）：兼容 "1.8.0_x" / "17.0.x" / "20.x" 三种格式
- [x] **子进程 JVM 定位统一**（`Utils.findJavaCommand()`）：优先当前 java.home，跨平台；ReadBoard / CaptureTsumeGo / LizzieFrame 三处嵌套 fallback 简化

### Phase 1：同步功能复活
- [x] **弈客棋盘同步**：OnlineDialog type1/5 从失效 socket.io 切回 HTTP 轮询（`refresh()` 每 10s 轮询 v1 golive/dtl）；BrowserFrame onAddressChange 触发 syncOnline（带 dedupe）
- [x] **野狐棋谱**：新建 `FoxApi.java` 直连 newframe.foxwq.com；`GetFoxRequest.java` 废弃黑盒 jar 改异步 HTTP；`FoxKifuDownload` 界面扩展为 5 输入框（登录账号 / 密码 / 搜索用户 / Token / Session）；修分页 bug（改用目标 uid）
- [x] **共享棋谱库稳健性**：SocketKifuSearch 加 15s 超时；Public/PrivateKifuSearch 空结果不再抛异常，改友好提示

### Phase 2：JDK 17 + JCEF 升级
- [x] **`--release 17`**：字节码 major version 61（已验证）
- [x] **jcefmaven 95 → 135.0.20**（chromium-135），BrowserFrame CefAppBuilder API 兼容
- [x] **natives 按平台 profile 分发**：`-Pnative-windows-amd64` / `-Pnative-linux-amd64` / `-Pnative-macosx-amd64` / `-Pnative-macosx-arm64`；默认轻量 32MB，带 natives 147MB
- [x] **远程引擎 ganymed-ssh2 → com.github.mwiede:jsch 2.28.6**：迁移 4 个文件（SSHController、AnalysisEngineSSHController、ContributeSSHController、EstimateEngineSSHController）
- [x] 从零 clean 构建 + shade 打包通过；jsch 已入 jar，旧 ch.ethz.ssh2 已移除

---

---

## ⚠️ 本批次封档说明（Important / Known Issues）

> 以下为已实现但**尚未实机闭环**的运行时项，封档前如实记录，避免误判为全部完成。

### 1. JCEF 135 内部浏览器在 macOS JDK 26 上未能唤起（未解决）
- **现象**：GUI 正常启动并存活（无崩溃），但点击「弈客直播」（Menu.yikeLive → LizzieFrame.bowser() with isYike=true）**没有弹出内部浏览器窗口**。
- **已推进**：JCEF 已从 95 升到 135（chromium-135），Phase 2 已验证 natives 能解压加载（m2 仓库 `jcef-natives-macosx-arm64/...135.0.20...` 成功解出），主进程稳定。
- **未通过**：在 JDK 26 上实际渲染/弹窗环节未成功，未定位到具体崩溃原因（无 LastErrorLogs）。
- **下一步建议**：a) 换 JDK 17 或 21（JCEF 135 官方支持 8-21，JDK 26 可能超支持范围）；b) 抓 CEF 子进程/dylib 加载日志；c) 三平台分别回归。

### 2. 野狐「按用户名搜该uid全部对局」受平台 session 限制（未完全可用）
- 登录、查 uid（柯洁→6757425）均已实测成功；但拉棋谱列表 `TXWQFetchChessList` 返回 `105207` 空列表，因需要 `session`，而 session 无法从登录 API 程序化获得（foxwq-sgf-dl 作者亦证实）。
- 已做 UI：野狐窗口 5 输入框（登录/密码/搜索用户/Token/Session），需**手动填有效 session** 才能拉列表。详见 remake.md §2.2.4。

### 3. 弈客「贴链接同步」逻辑已实现但未端到端确认
- 代码路径：OnlineDialog type1/5 改 HTTP 轮询 + BrowserFrame onAddressChange 触发，v1 golive/dtl 实测匿名可用。
- 需实机：粘贴直播间链接 → 5 秒内同步、每 10 秒自动更新、停止同步、错误 URL 提示。

### 4. 你选择封档，以下跨平台项留待后续
- 三平台回归（Win/mac/Linux × HiDPI、JCEF 渲染、远程 SSH 引擎、OCR 子进程、双屏）未做。
- 野狐拉取需真实 session（仅手动填充路径，未做自动抓包集成）。

### 5. 构建状态（已确认）
- 全量 `mvn clean package` 通过；Java 17 字节码（major 61）；默认轻量 32MB，`-Pnative-macosx-arm64` 产出 147MB（含 chromium arm64 natives）。

---

## 🔄 当前状态与待回归项

### 需实机回归（编译已验证，运行时待确认）
- [ ] **弈客同步**：粘贴直播间链接 → 5 秒内同步、每 10 秒自动更新；停止同步；错误 URL 提示
- [ ] **野狐**：登录成功 / 密码错误 / 风控三类场景；「搜索用户」解析 uid 后拉列表；翻页边界（>100 局）；下载 SGF 与鹰眼联动 —— ⚠️ 必须手动填 Session（见 remake.md §2.2.4）
- [ ] **JCEF 135 实际渲染**（关键！沙箱只验证到 natives 解压加载）：在 JDK 26 上打开弈客/野狐浏览器页面，确认能弹窗正常显示
- [ ] **远程 SSH 引擎**：用 jsch 连新版 OpenSSH 服务器（验证 ganymed→jsch 迁移）
- [ ] **共享棋谱库**：服务失联时报友好错误而非崩溃
- [ ] **OCR 棋盘同步（Java 版）**：macOS/Windows 屏幕录制权限、框选、双向落子
- [ ] **三平台回归清单**（Windows / macOS arm64 / Linux）：
  - [ ] 启动、HiDPI 100%/125%/150%/200% 无模糊错位
  - [ ] JCEF 打开弈客/野狐页、远程 SSH 引擎、OCR 子进程
  - [ ] 双屏/混合 DPI

---

## ⏳ 待实施（Phase 3–6）

### Phase 3：UI 现代化（现代观感）
- [ ] 引入 FlatLaf + 深色模式开关（替换 Lizzie.java LAF 设置）
- [ ] 设计令牌：硬编码色值收敛 UIManager / 主题 JSON（例：LizzieFrame.java:461-463、:711-713）
- [ ] JFont* 控件族适配
- [ ] 大文件拆分：LizzieFrame 12450 行 → 面板/动作/菜单（菜单入口 setJMenuBar 在 LizzieFrame.java:894）

### Phase 4：毛玻璃与无边框（用户点名特效）
- [ ] GlassWindow 工具类（JNA：Windows DwmSetWindowAttribute Mica/Acrylic；macOS NSVisualEffectView 注入）
- [ ] 主窗 undecorated + 自绘标题栏（三键 + 拖拽 + 双击最大化 + snap）
- [ ] 毛玻璃开关/强度/降级纯色；Linux 降级路径
- 前置：运行环境需 JDK 17+（当前已编译成 17 字节码，运行验证在用 JDK 26）

### Phase 5：性能
- [ ] 脏矩形 + 离屏缓存（LizzieFrame.paintMianPanel :3613/:3629）
- [ ] drawStones 19 线程去 EDT 阻塞（BoardRenderer.java:1300-1321）
- [ ] 替换 getScaledInstance（BoardRenderer.java:3785）
- [ ] Swing 线程纪律整改（Leelaz.java:1426、LizzieFrame.java:1494 → invokeLater）

### Phase 6：发布工程
- [ ] jpackage 三平台打包脚本 + GitHub Actions 矩阵 + 签名（mac 公证 / win 证书）
- [ ] 更新检查 HTTPS 化（替换 SocketCheckVersion.java 明文 TCP）
- [ ] 自动回归：JUnit5（SGFParser/Zobrist/Board/Base64AES/checkUrl 正则）+ jdeps 内部 API 扫描入 CI

---

## 🔧 构建命令备忘

```bash
# 默认轻量构建（32MB，运行时下载 JCEF natives）
mvn clean package

# 打包当前平台 natives（离线可用，macOS arm64 约 147MB）
mvn clean package -Pnative-macosx-arm64

# 其它平台
mvn clean package -Pnative-windows-amd64
mvn clean package -Pnative-linux-amd64
mvn clean package -Pnative-macosx-amd64
```

## 📎 关联文档
- 完整技术细节 & 根因证据：见 `remake.md`
- 野狐 session 限制的抓取图文步骤：remake.md §2.2.4
