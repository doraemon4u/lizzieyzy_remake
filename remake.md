# LizzieYzy 重构审计与改造手册（remake.md）

> 本文档是 lizzieyzy v2.5.3 的**全面审计报告 + 分步重构方案 + 长期参考手册**。
> 审计时间：2026-08；审计基线：`main` 分支 HEAD `741c301`（最后提交 2023-08-18）。
> 文中所有结论都标注了证据等级：**已实测**（本机/线上验证）、**反编译证实**（从字节码还原）、**推测**（合理推断，需复核）。
> 注意：本机 DNS 解析到 198.18.0.0/15 保留段（存在透明代理），线上探测结论建议在正常网络环境复核一次。

---

## 目录

1. [项目现状快照](#1-项目现状快照)
2. [失效功能专项审计](#2-失效功能专项审计)
   - 2.1 弈客围棋棋盘同步
   - 2.2 野狐围棋棋谱同步
   - 2.3 其他外部依赖功能风险清单
3. [UI 现代化与毛玻璃改造方案](#3-ui-现代化与毛玻璃改造方案)
4. [构建 / JDK / 依赖升级路线](#4-构建--jdk--依赖升级路线)
5. [重构总路线图（Phase 0–6）](#5-重构总路线图phase-06)
6. [回归测试与验收清单](#6-回归测试与验收清单)
7. [参考资源](#7-参考资源)

---

## 1. 项目现状快照

| 项目 | 值 |
|---|---|
| 语言 / 框架 | Java 8（pom.xml:23-25，source/target 1.8）+ Swing（无任何现代 LAF） |
| 版本 | `yzy2.5.3`（pom.xml:9）；最后提交 2023-08-18 |
| 代码量 | 310 个 Java 文件，约 **11.7 万行** |
| 包结构 | `featurecat.lizzie.{analysis,gui,rules,theme,util}` |
| 入口 | `featurecat.lizzie.Lizzie`（Lizzie.java:57） |
| 构建 | Maven，shade 打包单个 fat-jar（pom.xml:38-59）；**无 CI、无打包脚本、无测试**（surefire 2.9 且 skip=true，pom.xml:63-67） |
| 内嵌资源 | 4 个运行时解包的外挂 jar（Utils.java:908-944）：`FoxRequest.jar`、`readboard-1.6.2-shaded.jar`、`CaptureTsumeGo1.2.jar`、`invisibleFrame.jar` |
| 官方外部依赖 | org.json 20180130、jcefmaven 95.7.14.11、ganymed-ssh2 build210、swingx-core 1.6.4、juniversalchardet 1.0.3、jhlabs filters 2.0.235、Java-WebSocket 1.5.0、socket.io-client 1.0.0（pom.xml:123-188） |
| 私有服务器 | `lizzieyzy.cn` 明文 TCP：更新检查/OGS 登录 3045、公共棋谱搜索 3285、棋谱库编辑 3075、上传 3085、下载 3105（SocketCheckVersion.java:30 等） |
| 内置浏览器 | JCEF（jcefmaven），Chromium **95**（2021 年），无 arm64 natives |

**一句话总评**：源码对 JDK 17/21 的源码级兼容障碍几乎为零（内部 API 仅剩注释），最大的三个问题分别是：① 外部服务/协议全面过期（弈客、野狐、lizzieyzy.cn）；② JCEF Chromium 95 在 2025 年 macOS/Windows 上不可用且无 Apple Silicon 原生库；③ Windows 死路径假设 + Java 8 时代的手写 HiDPI 补偿与现代系统的冲突。

---

## 2. 失效功能专项审计

### 2.1 弈客围棋棋盘同步（已失效）

#### 2.1.1 调用链（现状代码）

```
菜单「弈客直播 / 弈客大厅 / 野狐」：BottomToolbar.java:713-769、Menu.java:3961-3991、热键 Input.java:490
  → LizzieFrame.bowser(url, title, isYike=true)：LizzieFrame.java:8747-8769
    → new BrowserFrame(url, title, true)：JCEF 窗口，BrowserFrame.java:47-101
      → 页面内点击直播间（旧版会弹新窗）→ onBeforePopup 触发：BrowserFrame.java:220-228
        → Lizzie.frame.syncOnline(target_url)：LizzieFrame.java:8720-8731
          → onlineDialog.applyChangeWeb(url)：OnlineDialog.java:2594
            → checkUrl() 正则提取 id/roomId：OnlineDialog.java:341-424
              → ajaxUrl = https://api.yikeweiqi.com/golive/dtl?id=..&flag=1 ：OnlineDialog.java:363
                → type 1/5 → req2()：OnlineDialog.java:439-441、1984+
                  → socket.io 连接 rtgame.yikeweiqi.com（字节数组 c1：OnlineDialog.java:128-131）
                    → login / entry_room / init(move) → sync() → 主棋盘
```

**旁路**：OCR 通用棋盘同步（`syncBoardJava`，readboard-1.6.2-shaded.jar 截屏识别）不依赖弈客 API，代码仍在（ReadBoard.java:100-192、321-762），理论仍可用。

#### 2.1.2 根因分析（按概率排序）

| # | 根因 | 证据 | 等级 |
|---|---|---|---|
| 1 | **rtgame.yikeweiqi.com socket.io 实时通道已下线** | 实测 `rtgame.yikeweiqi.com:443` TLS 握手直接失败（HTTP 000）；当前 SPA 的 desktop.js 已不再引用 socket.io，改用 Centrifugo 式 WS：`wss://golive-api.yikeweiqi.com/connection/websocket`（直播）、`wss://game-server.yikeweiqi.com/connection/websocket`（对局），实测两端点存活（400 = 正常拒绝非 Upgrade 请求） | **已实测** |
| 2 | **type1/5 没有 HTTP 轮询兜底** | OnlineDialog.java:612 明写 `// 弈客暂时不需要刷新了`，轮询条件 `type == 101` 永不成立；type1/5 只走 socket.io，通道一挂功能即死 | 源码 |
| 3 | **触发机制失效** | 新版 SPA 直播间 `/live/room/:id/:hall/:room` 是**页内 Vue 路由**，不再 window.open，`onBeforePopup`（BrowserFrame.java:220）不再触发；`onAddressChange`（:181-183）只更新地址栏，没有调用 syncOnline | **已实测**（网页结构）+ 源码 |
| 4 | **JCEF Chromium 95 根库过旧（推测）** | 站点证书已换为 `Xcc Trust DV SSL CA`（Beijing Xinchacha，2025-12 签发，链到 Certum Trusted Network CA）。Certum 根在 Java 8 cacerts 中存在，但 Chromium 95 的 NSS 根库较老；且 Chromium 95 无 macOS arm64 natives，Apple Silicon 上大概率直接起不来 | **推测** |
| 5 | **v2 API 需要 usertoken** | 实测 v2 类接口返回 `invalid access token`；v1 `golive/dtl` 仍匿名可用（见下） | **已实测** |

#### 2.1.3 关键实证（2026-08 实测）

- ✅ `https://api.yikeweiqi.com/golive/dtl?id=18328`（及 `&flag=1`）**仍返回 200 + 完整 SGF**（`{"Status":1200,"Result":{"live":{"Content":"(;GM[1]..."}}`），任意 UA（含 `Java/1.8.0`）均可，CORS 开放，证书链 Certum（Java 8 可验证）。
- ❌ `golive/list` 等列表接口返回 `Status 1405 "请将您的客户端更新至最新版"`——列表类接口已加 AppKey/CurTime/Nonce/CheckSum 签名头（见响应 `Access-Control-Allow-Headers`）。
- ✅ 新 WS 端点存活；❌ 旧 `rtgame.yikeweiqi.com` 不可达。

#### 2.1.4 修复方案（推荐组合：A+B，可选 C）

**方案 A（最快见效，改动小）：恢复 HTTP 轮询兜底，让 v1 `golive/dtl` 重新驱动同步**
1. `OnlineDialog.java:438-458`（`proc()` 的 case 1/5）改为调用 `refresh(...)` 走 HTTP（参考 case 2 的写法 :444）。
2. `OnlineDialog.java:612`：把 `type == 101` 放宽为 `type == 1 || type == 5 || type == 101`，使定时器真正按 `txtRefreshTime` 轮询 `ajaxUrl`（v1 dtl 返回的 `Content` 就是随盘更新的 SGF，`parseSgf()` :461-541 已能解析该结构）。
3. 效果：用户把直播间 URL 粘进「在线对弈」对话框即可实时同步；对旧接口完全兼容（接口未变）。

**方案 B（修复触发路径，恢复一键同步体验）**
1. `BrowserFrame.java:178-184`：`onAddressChange` 里在 `address_.setText(url)` 之后加 `if (isYike) Lizzie.frame.syncOnline(url);`（注意节流：同一 URL 只触发一次，Vue 路由切换频繁）。
2. 可选增强：对 `home.yikeweiqi.com/#/live/room/...` 这种 hash 路由，JCEF 的 `onAddressChange` 在 hash 变化时也会回调，直接可用。
3. 兜底：`BrowserFrame.java:166-175` 地址栏回车已有 syncOnline 调用，保留。

**方案 C（中长期，恢复低延迟推送）：逆向新 Centrifugo WS 协议**
1. 抓包新版 web 客户端 `wss://golive-api.yikeweiqi.com/connection/websocket` 的握手（centrifugo 的 connect/subscribe 协议是公开规范：JSON 帧，connect/subscribe/publish 方法）。
2. 在 `OnlineDialog.java:1984+` 的 `req2()` 中用 Java-WebSocket 替换 socket.io 客户端实现 Centrifugo 客户端，订阅直播间 channel 的 move 事件。
3. 涉及鉴权：需先获取连接 token（可能要按新 SPA 的方式调登录/游客接口），风险中等。
4. 同步逻辑（`sync()` :2339+、`parseSgf()`）可复用。

**方案 D（预防性）：升级 JCEF（必做，见 §4）**，否则 macOS/Windows 上内置浏览器本身打不开。

### 2.2 野狐围棋棋谱同步（已失效）

#### 2.2.1 调用链（现状代码）

```
菜单「野狐(腾讯)棋谱」：Menu.java:3989-3998、BottomToolbar.java:741-748
  → LizzieFrame.openFoxReq()：LizzieFrame.java:12129
    → new FoxKifuDownload()：FoxKifuDownload.java:63-252（输入野狐昵称）
      → getFoxKifus()：FoxKifuDownload.java:274+（发送 "user_name <昵称>" 命令，:292）
        → GetFoxRequest：启动外部黑盒进程 foxReq/FoxRequest.jar，stdin/stdout 行协议
          （GetFoxRequest.java:22-77；Windows 上还依赖硬编码 jre\java17\bin\java.exe 等死路径，Utils.java:59-61）
          → jar 内部直连腾讯旧移动端 CGI（4 条 URL 见 2.2.2 根因 #1）
            → 返回逐行 JSON：GetFoxRequest.java:99-113 → FoxKifuDownload.receiveResult：FoxKifuDownload.java:305-495
              → 翻页 "uid <uid> <lastChessid>"：FoxKifuDownload.java:261
              → 取谱 "chessid <chessid>"：FoxKifuDownload.java:149、:568 → json "chess" 字段 = SGF → SGFParser.loadFromString：FoxKifuDownload.java:476-479
```

**旁路**：公共/个人共享棋谱库（PublicKifuSearch/PrivateKifuSearch）→ `SocketKifuSearch`（lizzieyzy.cn:3285）→ `SocketGetFile`（:3105），与 FoxRequest 无关，是另一条链（见 2.3）。

#### 2.2.2 根因分析（按概率排序）

| # | 根因 | 证据 | 等级 |
|---|---|---|---|
| 1 | **腾讯旧 CGI 参数/鉴权 schema 变更 + 野狐/腾讯分家** | 反编译 `FoxRequest.jar` 还原出 4 条 URL：`https://happyapp.huanle.qq.com/cgi-bin/CommonMobileCGI/TXWQFetchChessList?type=4&username=<name>&accounttype=0&clienttype=0`（user_name 分支）、`http://happyapp.huanle.qq.com/...TXWQFetchChessList?type=4&lastCode=<id>&uid=<uid>&RelationTag=0`（uid 分支）、`http://happyapp.huanle.qq.com/...TXWQFetchChess?chessid=<id>`（取谱）。实测列表接口仍存活但任何旧参数组合都返回 `{"result":1000002,"resultstr":"您提交的参数不正确"}`；社区（featurecat/go-dataset#1，2024 评论）证实参数已从 `username`/`FindUserName` 演变为 `searchkey`，且野狐与腾讯分家、腾讯侧另起 `txwq.qq.com`，野狐数据在 `newframe.foxwq.com` | **反编译证实 + 已实测** |
| 2 | **FoxRequest.jar 请求格式本身就不对** | 反编译看到：`user_name` 分支缺 `lastCode/uid/RelationTag`；`uid` 分支缺 `FindUserName`；`sendPost` 只 flush 不写 POST body（空 body）；另有 2 参变体连协议前缀都没有（不可达）。与真实 schema 对不上——这个黑盒从始至终就是靠运气 | **反编译证实** |
| 3 | **老棋谱数据清理/迁移** | 实测 `TXWQFetchChess`（取单谱）端点返回 `-3 FetchChessFromDB Failed`，老 chessid 已查不到 | **已实测** |
| 4 | **share.foxwq.com 分享页风控** | 实测 `http://share.foxwq.com/...` 301 → HTTPS，HTTPS 直接 403（WAF/UA 校验），旧分享链接同步方式（OnlineDialog type 3/4）也受波及 | **已实测** |
| 5 | 次要问题：`GetFoxRequest.java:26` 的 `-Dfile.encoding=utf-8` 位置错误（放在 `-jar` 之后）；无进程退出回收、无超时 | 源码 | 源码 |

#### 2.2.3 关键实证：野狐当前真实 API（2025-2026 仍在维护的社区实现 `yiqiaoli/foxwq-sgf-dl` 逆向结果，本机已逐一探测）

| 步骤 | 端点 | 要点 |
|---|---|---|
| 登录 | `POST https://newframe.foxwq.com/cgi/LoginByPassword` | JSON body：`{"device_id_md5":"e7ab56438d7225217c9a417a87031fef","client_type":13,"password":"<md5(密码)>","user_identifier":"<账号/昵称>"}` → 返回 token + session |
| 用户信息 | `GET https://newframe.foxwq.com/cgi/QueryUserInfoPanel` | `srcuid, dstuid / username, time_stamp` |
| 棋谱列表 | `GET https://newframe.foxwq.com/chessbook/TXWQFetchChessList` | `type=1, fetchnum, dstuid, srcuid, time, token, session, lastCode`（分页）→ `chesslist[]`，字段与 FoxKifuDownload.java:350-369 解析的 `starttime/chessid/movenum/blackuid/whiteuid` **完全一致**（响应 schema 没变，变的是域名 + 鉴权） |
| 单谱 | `GET https://newframe.foxwq.com/chessbook/TXWQFetchChess` | `chessid, trans, srcuid, time, token, session` → `chess` = SGF（注意 `KM[375]` 需修正为 `KM[7.5]`） |

**必带请求头**：`User-Agent: UnityPlayer/2022.1.16f1 (UnityWebRequest/1.0, libcurl/7.84.0-DEV)`、`X-Unity-Version: 2022.1.16f1`、`referer: http://www.qq.com`、`Content-Type: application/json`。
实测 `newframe.foxwq.com` 的 login 与 list 端点均返回 HTTP 200（服务在线）。

#### 2.2.4 修复方案（已实施，含关键平台限制发现）

**已落地：废弃黑盒 jar，新建 `FoxApi.java` 直连 newframe.foxwq.com，`GetFoxRequest.java` 改为异步 HTTP 驱动**
- 登录（账号密码 MD5）✓ 已实测可成功拿到 token + uid
- 按用户名解析 uid（`QueryUserInfoPanel`）✓ 已实测（如 "柯洁"→uid 6757425）
- 拉棋谱列表（`TXWQFetchChessList`）—— **受限于 session，见下方硬限制**

**⚠️ 平台硬限制（2026-08 实测确认）：`session` 无法程序化获得**
- `TXWQFetchChessList` 返回 `{"result":105207}`（list 空）当且仅当 `session` 无效/缺失；实测 session 填 token/uid/0/PHPSESSID 均被拒。
- 登录 API `LoginByPassword` 只返回 `token` + `uid`，**不返回 `session`**；且该 `session` 无法从 token 推导。
- 维护 `foxwq-sgf-dl` 的作者在 README 明确承认：*"haven't yet figured out how session is calculated… currently hardcoded in the config file"*。
- 因此「按用户名精确拉取该 uid 全部对局」**无法纯 HTTP 自动化**，需要用户提供真实 session。

**已实施的用户体验（手动填 session）**
- 野狐窗口现有 **5 个输入框**：用户名（登录账号）、密码、搜索用户（目标棋手）、Token（可选）、Session（必须）。
- 程序优先用「手动填的 Session」；未填时尝试登录拿 token，但会提示「缺少 session 请手动填写」。
- 逻辑：登录/注入凭证 → 用「搜索用户」解析 uid → 拉该 uid 第一页对局（分页走 `getCurrentDstUid` 而非旧 `myUid`，修复了"搜别人时分页会串档"的隐性 bug）。

**如何抓取真实 session（图文步骤）**
1. 打开野狐 **Windows 桌面客户端**（`newframe.foxwq.com` 只被客户端调用；网页 H5 用 `PHPSESSID` 是另一套鉴权，不通用）。
2. 用 **Fiddler / Charles** 开启 HTTPS 抓包（需安装并信任其根证书）。
3. 在客户端里打开任意玩家的「棋谱/复盘」列表。
4. 在 Fiddler 里找到发往 `newframe.foxwq.com/chessbook/TXWQFetchChessList` 的请求。
5. 从查询参数里复制 `token` 和 `session`，粘贴到程序的 Token / Session 框（会被记住）。
6. 点搜索即可拉取。

**遗留：免 session 的取单谱备用路径**
- 网页分享棋谱用 `h5.foxwq.com/yehunewshare/?chessid=<id>`（HOME 首页棋谱链接即此格式），其内部数据接口为客户端渲染、路径藏在 uni-app chunk 里，尚未逆向出免鉴权端点；`www.foxwq.com` 主站（PHP）用 `PHPSESSID` cookie 鉴权，是另一套。可作为后续 Phase 探索方向，不阻塞本次交付。


### 2.3 其他外部依赖功能风险清单

| 功能 | 代码位置 | 依赖 | 实测状态 | 处置建议 |
|---|---|---|---|---|
| 公共/个人共享棋谱搜索 | 菜单 Menu.java:3951→LizzieFrame.java:8830（公开）、Menu.java:3905→LizzieFrame.java:10207（个人）；查询 PublicKifuSearch.java:319-416、PrivateKifuSearch.java:291-402、SocketKifuSearch.java:30 | `lizzieyzy.cn:3285` 明文 TCP（`SktINFOStart`+Oracle SQL）+ AES-128-CBC，**密钥明文躺在源码**（key=`iyekeeaysueeaesk` iv=`s6st73f41adc4c5d`，Utils.java:62-65、doEncrypt :216-220、Base64AesEncrypt.java） | 端口可连但**发送数据即 RST，服务已失联**；返回空时 `sqlResult.get(0)` 抛未捕获 IndexOutOfBoundsException（PrivateKifuSearch.java:407、PublicKifuSearch.java:425），UI 静默无反应 | 服务端无法自愈。**短期**：补空结果判断与友好错误提示；**中期**：迁到开源棋谱库 API（见 §7）或自建服务；**长期**：功能下线或社区化 |
| 更新检查 / OGS 聊天登录 | SocketCheckVersion.java:30、SocketLoggin.java:27 | `lizzieyzy.cn:3045` 明文 TCP | 端口可连，响应未知（同上服务器） | 改为 HTTPS 轮询 GitHub Releases API（更新检查）；OGS 登录换官方 API |
| 棋谱库上传/编辑/下载 | SocketUpfile.java:39（3085）、SocketEditFile.java:36（3075）、SocketGetFile.java:28（3105） | 同上 | 同上，失联 | 同「共享棋谱」处置 |
| 野狐/腾讯旧分享链接直播（type 3/4） | OnlineDialog.java:401-418、705-775、:112-126 | `wshall.huanle.qq.com`、`wshall.qq.com` | wshall.huanle.qq.com 443 握手失败（**已实测**） | 腾讯侧通道确认死亡，建议移除 type 4 分支或并入野狐新 API |
| 弈客大厅/直播页内置浏览 | BottomToolbar.java:714-738 | JCEF Chromium 95 | 见 2.1 根因 #4 | 随 JCEF 升级修复 |
| 在线对弈对话框帮助文本 | OnlineDialog.java:234 | 示例 URL 已是 2019 年格式 | — | 更新为新格式示例 |
| 版本号解析 | Lizzie.java:84-88 | `java.version` 按第一个 `.` 截断，JDK 8 得 "1" | — | 升级 JDK 时重写为 Runtime.version().feature() |

> **通用建议**：所有外部 URL/域名/端口（弈客、野狐、lizzieyzy.cn 五端口、更新检查）目前散落硬编码在各处，应集中收敛到 `Config`（或独立 `Endpoints` 常量类）支持运行时覆盖，避免每次接口变更都要发版。

---

## 3. UI 现代化与毛玻璃改造方案

### 3.1 现状特征（审计结论）

- **全自绘渲染**：主棋盘/工具条/选点全部画进自绘面板（`LizzieFrame.paintMianPanel`，LizzieFrame.java:3613；每次重绘 `new BufferedImage(width, height)` 全窗重建，:3629 附近，无脏矩形、无 VolatileImage、无 GPU 加速）。棋子层有 zobrist 判脏缓存（BoardRenderer.java:1272-1321，`cachedZhash`/`cachedStonesImage`），但仅棋子层享受缓存。
- **主题系统**：`theme/Theme.java` 只覆盖棋盘视觉（棋盘/棋子/背景图、胜率线颜色等，theme.txt JSON），**控件色硬编码、无深色模式**——例：blunder 条色 `new Color(225,225,225)`（LizzieFrame.java:461-463）、表头色 `new Color(208,208,208)`/`new Color(178,178,178)`（LizzieFrame.java:711-713）。5 个内置主题：Custom/Fast/Megapack/sabaki/yasnaya。
- **窗口装饰**：主窗用系统装饰；浮窗/独立盘/识别工具等去装饰（`setUndecorated`）。65+ 个 JDialog 各自手写布局和配色，无统一设计系统。
- **HiDPI**：靠 `AwareScaled`（AwareScaled.java:11-32，一个 1×1 隐藏窗口探测 Graphics2D scale）+ 手动缩放补偿，散布三处：`Utils.ajustScale`（Utils.java:69-84，绘制时按 1/scale 反缩放）、`Lizzie.setFrameSize`（Lizzie.java:168-177，按 javaVersion/sysScaleFactor 魔法数调窗口尺寸）、`Lizzie.javaScaleFactor` 透传。**这套方案在 JDK 9+ 原生 HiDPI 下会与系统缩放叠加，产生模糊/错位**；JDK 8 在 macOS 上则相反（不支持 Retina 缩放，靠这套 hack 活着）。
- **字体**：内置 OpenSans/SourceCodePro（resources/fonts），自定义 `JFont*` 控件族统一字号。

### 3.2 性能瓶颈（按收益排序）

1. **每帧全窗重建 BufferedImage**（LizzieFrame.paintMianPanel，LizzieFrame.java:3613；`new BufferedImage(width, height, TYPE_INT_ARGB)`，:3629）：改脏矩形 + 离屏缓存，只在棋盘/工具条变化时重绘对应区域。
2. **`drawStones` 每次棋盘变化按列开 19 个裸线程 + `CountDownLatch.await()` 阻塞 EDT**（BoardRenderer.java:1300-1321）：改为固定线程池/预渲染，或直接单线程画完（现代 CPU 上 19×19 颗石头单线程毫秒级）。
3. **`Image.getScaledInstance(size, size, SCALE_SMOOTH)` 慢速插值**（BoardRenderer.java:3785）：换 `Graphics2D.drawImage` + `RenderingHints.VALUE_INTERPOLATION_BICUBIC`，或预生成多档位缓存。
4. **Swing 线程纪律**：GTP 读取线程直接调 `Lizzie.frame.refresh()`（Leelaz.java:1426）；`showPlayouts.scheduleAtFixedRate` 定时线程直接读写 Swing 状态（LizzieFrame.java:1494）。全部收口到 `SwingUtilities.invokeLater` / `SwingWorker`。
5. 落子动画、阴影等用 `BufferedImage` 软渲染即可，不需要上 GPU；若要激进优化，再评估 Java2D OpenGL pipeline（`-Dsun.java2d.opengl=true`）或 Metal（macOS JDK17+ 的 Metal pipeline）。

### 3.3 毛玻璃（Frosted Glass）可行性与平台方案

> 结论：**Swing 下可实现，但需要去装饰窗口 + JNA 调用原生 API**。建议先升 JDK 17 再动手（Java 8 下 macOS 透明窗口有已知缺陷，且 JNA 也要新版）。

**Windows 11（Mica / Acrylic）**
- 用 JNA 调 `dwmapi.dll`：`DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE=38, 2/*Mica*/, 4)` 或 DWMWA_MICA_EFFECT（build 22000+），Acrylic 用 `SetWindowCompositionAttribute`（未文档化结构体，JNA 需手写 struct）。
- 前提：窗口 `setUndecorated(true)` + `AWTUtilities.setWindowOpaque(false)`（JDK 9+ 用 `setBackground(new Color(0,0,0,0))`）。
- 已知社区库：**mica4j**（专门给 Swing/JavaFX 做 Mica 的 JNA 封装）——直接调研优先。
- 风险：Mica 只在 Win11 22000+ 生效；Win10 自动回退纯色/亚克力需要分支；无边框后要自绘标题栏（最小化/最大化/关闭 + snap 布局）。

**macOS（NSVisualEffectView）**
- 用 JNA 操作 Objective-C runtime：向窗口的 NSWindow 的 contentView 注入一个 `NSVisualEffectView`（material 用 NSVisualEffectMaterialHUDWindow/UnderWindowBackground/Popover），blendingMode=behindWindow。
- 前提：`setUndecorated(true)` + 透明背景；**必须保持窗口 contentView 的 wantsLayer 设置正确**，否则毛玻璃区域变黑。
- 风险：JDK 8 的 Aqua LAF 下透明窗口 bug 多（拖动残影、无法聚焦），**强烈建议 JDK 17+**；全屏/分屏（Stage Manager）下视觉效果需回归测试；Sonoma+ 无重大变化。
- 参考实现：社区有 `SwingNSVisualEffectView` 类（Gist/博客）可直接改造。

**Linux**：跳过（Wayland 无统一方案，KDE 模糊由合成器管）。做「纯色半透明」降级即可。

### 3.4 UI 现代化分阶段方案

| 阶段 | 内容 | 涉及文件 | 工作量（估） |
|---|---|---|---|
| **P0 地基** | 升 JDK 17 + 删除 AwareScaled 手动补偿（AwareScaled.java:11-32、Utils.ajustScale :69-84、Lizzie.setFrameSize :168-177、Config.isScaled 相关），全面回归 HiDPI | Lizzie.java、AwareScaled.java、Utils.java、所有 setSize/setBounds 调用点 | 中 |
| **P1 LAF** | 引入 **FlatLaf**（支持 Java 8+，主题变量化，天然支持深色/高对比/系统缩放）替换系统 LAF；`JFont*` 控件族改为 FlatLaf 的 `Component.setFont` 策略；顺带清掉硬编码色（LizzieFrame.java:461-463、:711-713） | Lizzie.java:270-300（LAF 设置处）、全部 JFont* 类、LizzieFrame.java | 大 |
| **P2 设计令牌** | 把散落硬编码色值收敛为 `UIManager` 键 + 主题 JSON 扩展（theme.txt 增加 UI 段：背景、前景、强调色、圆角、透明度）；实现深色模式开关 | Theme.java、BoardRenderer、BottomToolbar、各对话框 | 大 |
| **P3 无边框+毛玻璃** | 主窗 `setUndecorated` + 自绘标题栏（原生 snap/阴影保留策略：Windows 调 DWM 扩展边框 `DwmExtendFrameIntoClientArea` 保留系统阴影和 snap；macOS 隐藏 titlebar 技巧）；按 §3.3 接 Mica/NSVisualEffectView；不透明度滑杆（已有类似配置可复用） | LizzieFrame（12450 行，谨慎拆分）、新 GlassWindow 工具类 | 大 |
| **P4 打磨** | 圆角面板、按钮 hover/press 态、落子/选点动效（Swing Timer 驱动）、高对比无障碍模式、启动画面现代化 | BoardRenderer、各类对话框 | 中 |

**动效/性能注意**：所有动画必须走 `javax.swing.Timer` 且只重绘脏区；毛玻璃区域叠加棋盘时注意对比度（毛玻璃上白字需阴影）。

---

## 4. 构建 / JDK / 依赖升级路线

### 4.1 JDK 兼容性结论

- 源码级：`sun.misc`/`Unsafe`/`com.sun.*`/`java.awt.peer` 仅剩注释（Base64AesEncrypt.java:9、Lizzie.java:302 等），Base64 已用 `java.util.Base64`。**JDK 17 直接编译大概率通过。**
- 运行时风险集中在三处：① JCEF（Chromium 95 无 arm64，且旧版 JCEF 在 JDK17 上有崩溃报告，必须升级）；② 硬编码 Windows JRE 子进程路径（Utils.java:59-61 + ReadBoard/GetFoxRequest/CaptureTsumeGo 里的 `Runtime.exec`）；③ `java.version` 解析（Lizzie.java:84-88）。

### 4.2 依赖替换表

| 现状 | 问题 | 替换为 |
|---|---|---|
| org.json 20180130 | CVE-2022-45688 / CVE-2023-5072 | org.json 20250517+ |
| jcefmaven 95.7.14.11 | Chromium 95 EOL、无 arm64 | **135.x**（先升 119.x 验证 builder API 再跳 135；BrowserFrame.java:59-101 的 CefAppBuilder 用法需适配） |
| ganymed-ssh2 build210 | 2011 停更；ssh-rsa 被新版 OpenSSH 禁用，远程引擎功能在新服务器上连不上 | com.github.mwiede:jsch（0.2.x） |
| socket.io-client 1.0.0 | 2017，拖旧 okhttp 2.x | 2.1.x（OnlineDialog.java:12-15、1984+）或随弈客方案 C 直接移除 |
| Java-WebSocket 1.5.0 | 旧 | 1.6.x |
| swingx-core 1.6.4 | 2014；仅用 OS 判断与 JXDatePicker | 1.6.5-1；OS 判断可内联删除 |
| juniversalchardet 1.0.3 | 停更 | 保留或换 icu4j |
| jhlabs filters 2.0.235 | 稳定停更 | 保留 |
| maven-compiler 3.8.1 / surefire 2.9 / shade 3.1.0 | 与新 JDK 不兼容 | 3.13 / 3.2+ / 3.6+；surefire 去掉 skip=true |

### 4.3 打包与 CI（从零建设）

1. **目标**：JDK 17 + `jpackage`：app-image → dmg（mac aarch64+x64）/ msi（win x64）/ deb（linux x64）。
2. **JCEF natives**：jcef-bundle 预置进包（去掉 BrowserFrame.java:328-341 的运行时下载），jpackage 用 `--app-content` 挂载；mac 双架构用 lipo 或两个安装包。
3. **子进程 jar**（readboard/CaptureTsumeGo/FoxRequest）：不再依赖 `jre\java17\bin\java.exe`，改用 `ProcessHandle.current().info().command()` 取当前 JVM 或 `System.getProperty("java.home")`。
4. **GitHub Actions 矩阵**：windows-latest / macos-latest（x64+arm64）/ ubuntu-latest × 每日 build + tag 发版，产物附 SHA256。
5. **更新检查**：改为 HTTPS GET GitHub Releases API（替换 SocketCheckVersion.java 的明文 TCP）。

---

## 5. 重构总路线图（Phase 0–6）

> 原则：每阶段可独立交付、可回滚；先修"能用"，再修"好看"，最后"加速"。每步给出验收标准。

| 阶段 | 目标 | 具体步骤 | 主要文件 | 验收标准 | 风险 |
|---|---|---|---|---|---|
| **Phase 0：基建** ✅已实施 | 现代工具链 | ① pom 升级 compiler/surefire/shade，开 `-Xlint`，`mvn -q package` 全绿；② 补 .gitignore 与 GHA「build」工作流；③ 修 `java.version` 解析（Lizzie.java:84-88 改 Runtime.version()）；④ 子进程 JVM 路径改 `java.home`（Utils.java:59-61、ReadBoard.java:121-185、GetFoxRequest.java:26-68、CaptureTsumeGo.java:56-115） | pom.xml、Lizzie.java、Utils.java、ReadBoard.java、GetFoxRequest.java、CaptureTsumeGo.java | 三平台 `mvn package` 成功且产出 fat-jar；Windows 无 jre 目录也能启动子进程 | 低 |
| **Phase 1：同步功能复活（本轮用户痛点）** ✅已实施 | 弈客+野狐可用 | ① 弈客方案 A（§2.1.4：proc case 1/5 走 HTTP + 放开 :612 轮询条件）+ 方案 B（BrowserFrame onAddressChange 触发）；② 野狐方案 A（§2.2.4：删除 GetFoxRequest 进程链，直连 newframe.foxwq.com 四步请求 + 登录框）；③ 更新 OnlineDialog 帮助文本与示例 URL；④ 修 PublicKifuSearch/PrivateKifuSearch 空结果静默崩溃，加服务失联提示 | OnlineDialog.java、BrowserFrame.java、FoxKifuDownload.java、GetFoxRequest.java（删除/重写）、PublicKifuSearch.java、PrivateKifuSearch.java、Config.java、l10n 全部语言文件 | 弈客直播间 URL 粘入 5 秒内同步、每 10 秒自动更新；野狐账号登录后列表/翻页/下载全通；共享棋谱库给出友好错误而非崩溃 | 中（野狐接口有封号/风控可能，需控制频率与 UA） |
| **Phase 2：JDK 17 + JCEF 升级** ✅已实施 | 平台兼容性 | ① pom `--release 17`（字节码 major 61）；② jcefmaven **95→135.0.20**（chromium-135），BrowserFrame CefAppBuilder API 兼容；③ natives 按平台 profile（`-Pnative-*`），默认轻量32MB；④ 远程引擎 **ganymed-ssh2→com.github.mwiede:jsch 2.28.6**，4个SSHController迁移完成 | pom.xml + 4个SSHController*.java | 全量编译+打包通过；JCEF135 natives 已解压加载；**⚠️ 封档实测：macOS JDK26 上「弈客直播」内部浏览器仍未能弹窗（JCEF135 渲染未实机成功），三平台回归待做** | 中高（JCEF135 在 JDK26 渲染失败，需换 JDK17/21 或抓 CEF 日志） |
| **Phase 3：UI 现代化** | 现代观感 | ① 引入 FlatLaf + 深色模式开关（替换 Lizzie.java LAF 设置）；② 设计令牌：硬编码色值收敛 UIManager/主题 JSON；③ JFont* 族适配；④ 大文件拆分（LizzieFrame 12450 行 → 面板/动作/菜单拆出；菜单入口 `setJMenuBar(menu)` 在 LizzieFrame.java:894，可整体迁出） | Lizzie.java、LizzieFrame.java（拆分）、BottomToolbar.java、Menu.java、Theme.java、JFont*.java、theme/*/theme.txt | 深色/浅色一键切换全窗口生效；主题 JSON 向后兼容 | 中 |
| **Phase 4：毛玻璃与无边框** | 用户点名的特效 | ① 新 GlassWindow 工具类（JNA：Windows DwmSetWindowAttribute Mica/Acrylic + DwmExtendFrameIntoClientArea；macOS NSVisualEffectView 注入）；② 主窗 undecorated + 自绘标题栏（三键 + 拖拽 + 双击最大化 + snap）；③ 设置项：毛玻璃开关/强度/降级纯色；④ Linux 降级路径 | 新增 GlassWindow/TitleBar 类、LizzieFrame、Config.java、ConfigDialog2.java | Win11 主窗 Mica 生效且棋盘对比度可读；macOS 毛玻璃+流畅拖动；开关关闭后行为同 Phase 3；无内存/闪烁回归 | 高（无边框窗口行为细节多，需双平台长时间试用） |
| **Phase 5：性能** | 低占用高帧率 | ① 脏矩形 + 离屏缓存（LizzieFrame.paintMianPanel）；② drawStones 线程池化去 EDT 阻塞（BoardRenderer）；③ 替换 getScaledInstance；④ Swing 线程纪律整改（后台回调 invokeLater）；⑤ 评测：落子延迟、CPU 占用基线对比 | LizzieFrame.java、BoardRenderer.java、SubBoardRenderer.java、FloatBoardRenderer.java、Leelaz.java（回调点） | 100 手棋谱拖进度条不掉帧；静止时 CPU 占用显著下降；无 Swing 线程违规告警 | 中 |
| **Phase 6：发布工程** | 现代分发 | ① jpackage 三平台打包脚本 + GHA 矩阵 + 签名（mac 公证 / win 签名证书）；② 更新检查 HTTPS 化；③ 自动回归（SGFParser/Zobrist/Board 的 JUnit5）+ jdeps 内部 API 扫描入 CI | .github/workflows/*.yml、打包脚本、pom.xml、SocketCheckVersion.java | 三平台安装包一键产出；OTA 更新可用；CI 每日构建 | 中 |

---

> **⚠️ 封档备注（2026-08）**：Phase 0–2 代码与构建均已落地并全量打包通过，但以下运行时项**尚未实机闭环**——① JCEF 135 内部浏览器在 macOS JDK 26 上点击「弈客直播」未弹窗（未定位）：JCEF 官方支持 JDK 8-21，建议改用 JDK 17 或 21 运行再验证；② 野狐拉列表受平台 session 限制，需手动填 session（见 §2.2.4）；③ 弈客贴链接同步逻辑已实现，需实机端到端确认。详见 todo.md「封档说明」。

## 6. 回归测试与验收清单

**自动化（Phase 0 起逐步补）**
- JUnit5 覆盖纯逻辑：`SGFParser`（含野狐 `KM[375]` 修正、GBK/UTF-8 探测）、`Zobrist`、`Board`（落子/提子/打劫）、`Base64AesEncrypt/Decipher`、`checkUrl()` 正则（把新旧弈客/野狐 URL 样本做成参数化测试——**这一步对同步功能防再失效至关重要**）。
- CI 中加 `jdeps --jdk-internals` 扫描，防止 JDK 内部 API 回潮。

**手动清单（每阶段发布前必做）**
- 弈客：新格式直播间 URL 同步、自动刷新、停止同步、错误 URL 提示。
- 野狐：登录成功/密码错误/风控三类场景；翻页边界（>100 局）；SGF 下载与鹰眼分析联动。
- 共享棋谱库：服务失联提示（当前会静默崩溃）。
- OCR 同步（Java 版）：macOS/Windows 屏幕录制权限弹窗、框选、双向落子。
- UI：深色/浅色切换、毛玻璃开关、双屏/混合 DPI、最小化恢复、全屏。
- 引擎：KataGo/Leela 分析、远程 SSH 引擎、批量分析、鹰眼。

---

## 7. 参考资源

**失效功能修复参考**
- 野狐棋谱现行 API 逆向：<https://github.com/yiqiaoli/foxwq-sgf-dl>（登录/列表/取谱四步流程 + UnityPlayer UA，2025 年仍在维护）
- 腾讯旧 CGI 参数演变的社区讨论（`username`→`searchkey`、野狐/腾讯分家 txwq.qq.com）：<https://github.com/featurecat/go-dataset/issues/1>
- FoxRequest 原外挂源码：<https://github.com/yzyray/FoxRequest>
- 弈客新 WS 协议参考：Centrifugo 协议文档 <https://centrifugal.dev/docs/transports/websocket>
- 弈客 API 实测（本手册）：`GET https://api.yikeweiqi.com/golive/dtl?id=<id>`（v1，匿名可用）；新 WS `wss://golive-api.yikeweiqi.com/connection/websocket`、`wss://game-server.yikeweiqi.com/connection/websocket`

**UI / 毛玻璃**
- FlatLaf：<https://www.formdev.com/flatlaf/>（Java 8+，深色/高对比/系统缩放）
- mica4j（Swing/JavaFX 的 Windows Mica JNA 封装）：<https://github.com/RelativityMC/mica4j>
- Windows 亚克力/云母：`DwmSetWindowAttribute` DWMWA_SYSTEMBACKDROP_TYPE(38) 文档 <https://learn.microsoft.com/en-us/windows/apps/desktop/modernize/apply-mica>
- macOS NSVisualEffectView：<https://developer.apple.com/documentation/appkit/nsvisualeffectview>（JNA 注入参考社区 SwingNSVisualEffectView 实现）
- JNA：<https://github.com/java-native-access/jna>

**JCEF / 构建**
- jcefmaven（版本选择与 natives 打包）：<https://github.com/jcefmaven/jcefmaven>
- JCEF 主仓库：<https://github.com/jcefbuild/jcef>
- jpackage 指南：<https://docs.oracle.com/en/java/javase/17/jpackage/packaging-overview.html>
- JDK 内部 API 迁移：`jdeps --jdk-internals`

**本仓库历史分支（勿作基线）**
- `jcefbrowser`（2022-02 实验快照）、`hex`、`moreKataParam`、`tsumego` 均为过期特性线，**以 main 为准**。

---

## 附：审计方法备忘（供后续复核）

1. 每次外部服务失效排查，先按「域名存活 → HTTPS/TLS → API 语义 → 参数 schema → 鉴权」五层逐层 curl 探测；
2. 反编译黑盒 jar 用 `javap -c -p` 或 CFR/Procyon，URL 用 `grep -a -o -E 'https?://...'` 直接抓；
3. 前端协议变化用浏览器 DevTools 抓 `websocket` 帧与 XHR 请求头（注意新版站点多为 SPA + 签名头）；
4. 本机 DNS 若解析到 198.18.0.0/15，说明有透明代理在劫持，探测结论需在干净网络复核。
