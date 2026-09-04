# forge-web — Forge ↔ WebSocket 桥 (MDC)

让浏览器用 **Forge 规则引擎**打一局 MTG:服务端跑一局 Constructed(1 个人类玩家经本桥操作 + 1 个 Forge AI),
把对局状态按 MDC 的 **GameView JSON 契约**推给浏览器,并接收浏览器动作回传给引擎。

对接前端:`MagicDraftCommunity/mdc-web/battle.html`(契约样例见同目录 `mock_gameview.json`)。

---

## 1. 环境 / 构建

Forge 用 Maven,需 **JDK 17**。本机安装:

```bash
brew install openjdk@17 maven
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:/opt/homebrew/opt/maven/bin:$PATH"
```

无头编译(引擎子集,已验证可过):

```bash
# 最小引擎子集
mvn -q -pl forge-game,forge-ai -am -DskipTests -Dmaven.test.skip=true compile

# 本桥(会带上 forge-gui / forge-gui-desktop)
mvn -pl forge-web -am -DskipTests -Dmaven.test.skip=true compile
```

> 依赖说明:`forge-web` 依赖 **forge-gui-desktop**,只为复用 `GuiDesktop` 的
> Swing EDT(Forge 的 Input 框架用 `FThreads.invokeInEdtLater` 派发提示,必须有可用 EDT)。
> 我们的 `WebGuiBase extends GuiDesktop` 把所有弹窗/音频/商店方法改成 no-op,可无头运行
> (`-Djava.awt.headless=true`),不弹任何窗口。参考自 desktop 测试里的 `HeadlessGuiDesktop`。

卡库数据来自 `forge-gui/res/cardsfolder/`,由 `FModel.initialize(...)` 加载(启动约数秒)。

---

## 2. 运行

### 2.1 冒烟验证(不接浏览器)

```bash
mvn -q -pl forge-web -am exec:java -Dexec.mainClass=forge.web.SmokeTest
```

- 启动一局 human vs AI,**自动过优先权**(相当于人类一直点 OK),AI 正常出牌。
- 每次状态变化的 GameView JSON 追加写入 `forge-web/smoke_gameview.jsonl`(每行一条)。
- 首个快照另存 `forge-web/smoke_first.json`;把它拷成 `mdc-web/mock_gameview.json` 即可用
  `battle.html` 直接渲染验证契约。
- 90 秒墙钟上限或对局结束即停。

### 2.2 WebSocket 服务(接浏览器)

```bash
mvn -q -pl forge-web -am exec:java -Dexec.mainClass=forge.web.BridgeApp
# 可选端口:-Dexec.args="8899"(默认 8899)
```

- 监听 `ws://localhost:8899`。浏览器连上后立即收到当前状态 JSON。
- 浏览器发 `{"id":"pass"}` / `{"id":"play","cardId":"123"}` 等回传动作。
- `battle.html` 目前是 fetch 静态 json 的原型,需要把其数据源改成 WebSocket(见下"前端对接")。

打包成可直接 java -jar 运行的胖包(可选,便于上服务器):可给 forge-web 加 shade/assembly 插件,当前未配置。

---

## 3. GameView → JSON 字段映射

序列化器:`forge.web.GameViewSerializer`(纯函数,无副作用,可单测)。

| 契约字段 | 来源 (Forge) | 备注 |
|---|---|---|
| `you` | 人类 `PlayerView` 在 `GameView.getPlayers()` 中的位次 → `p{n}` | |
| `turn` | `GameView.getPlayerTurn()` | |
| `priority` | 遍历 players 取 `PlayerView.getHasPriority()` | |
| `phase` | `GameView.getPhase()`(PhaseType)折叠为 7 段 | COMBAT_* → `COMBAT`;END_OF_TURN/CLEANUP → `END` |
| `prompt` | 最近一次 `showPromptMessage` 文本 | |
| `players[].id` | 位次 `p{n}` | |
| `players[].name/life` | `getName()/getLife()` | |
| `players[].handCount/libraryCount/graveyardCount` | `getZoneSize(ZoneType.*)` | |
| `players[].mana{W,U,B,R,G,C}` | `PlayerView.getMana(MagicColor.*)` | C = COLORLESS |
| `players[].battlefield[]` | `PlayerView.getBattlefield()` → cardRef | |
| `hand[]` | 人类 `PlayerView.getHand()` → cardRef | |
| `stack[]` | `GameView.getStack()`(StackItemView)→ cardRef + `controller`/`note` | note = `StackItemView.getText()` |
| `actions[]` | 由当前按钮 + 可选卡(setSelectables)推导 | 见下 |

**cardRef**(`GameViewSerializer.cardRef`):

| 字段 | 来源 | 备注 |
|---|---|---|
| `id` | 按英文名查 `card_index.json` → **Scryfall id**;查不到回退 `CardView.getId()` | 见 `GameViewSerializer.resolveImage` |
| `name` | `CardStateView.getName()`(英文) | 不变 |
| `zh` | `card_index.json` 的中文名 | 查不到留 `""` |
| `tapped` | `CardView.isTapped()` | |
| `types[]` | `CardTypeView` 的 supertypes + coreTypes 的 `name()` | 如 `["Basic","Land"]`、`["Creature"]` |
| `power/toughness` | 仅当 `isCreature()` 时取 `getPower/getToughness`,否则 `""` | 与契约一致 |
| `counters[]` | `GameEntityView.getCounters()`(Multiset) | P1P1→`+1/+1`,M1M1→`-1/-1` |
| `img` | `https://images.mtgch.com/zhs/normal/front/{id0}/{id1}/{id}.webp` | 有 Scryfall id 才填,否则 `""` |
| `imgen` | 同构 `sf`(英文图) | 供前端 `onerror` 回退;无 id 则 `""` |

> **中文/卡图索引**:`forge-web/src/main/resources/card_index.json`(35175 条,`{英文名:{id,zh,face?}}`),
> 由 `CardIndex`(单例)启动时从 classpath 加载(打进胖包,部署无需外置)。决策帧里的卡类选项也用同一
> `resolveImage` 填 `cardId/img/imgen`。双面牌:索引对背面名单独建 key(可能带 `face:"back"`),front 优先;
> 图 URL 模板固定用 `front/`,DFC 背面图暂用其正面路径(边角情况,前端 onerror 兜底)。

**actions 推导规则**(`WebGuiGame.buildActions`,启发式,后续可细化):
- OK 按钮可用 → `{"id":"pass","label":"过优先权 / OK"}`
- Cancel 可用 → `{"id":"cancel", ...}`
- 当前 `setSelectables` 的卡:在手牌 → `{"id":"play","label":"使用 X","cardId":...}`;否则 `{"id":"select",...}`

---

## 4. IGuiGame 决策方法接了哪些

桥有**两条**把引擎阻塞调用变成浏览器往返的通路:

### 4.1 优先权 / 攻击 / 阻挡 —— 经 Input 框架(action 协议)
引擎在 `InputPassPriority/InputAttack/InputBlock` 里通过
`showPromptMessage` + `updateButtons` + `setSelectables` 呈现提示,
体现在推送 JSON 的 `actions[]` 里;浏览器回传 `{id,cardId}`,由
`WebGuiGame.submitAction(id,cardId)` 映射到 `IGameController`:
- `pass`/`ok` → `selectButtonOk()`(过优先权 / 确认攻防)
- `cancel`/`endturn` → `selectButtonCancel()`
- `play`/`select` + cardId → `selectCard(cardView,null,null)`(使用牌 / 选卡 / 宣告攻击者 / 选阻挡)
- `concede` → `concede()`

### 4.2 弹窗式决策 —— 真正的浏览器往返(decision / decide 协议)
游戏线程在决策方法里调用 `awaitDecision(descriptor)`:先把一个 `decision` 对象塞进推送 JSON,
然后阻塞在一个 `CompletableFuture` 上;浏览器(或无头自动应答)回传 `{id:"decide",...}`,
`WebGuiGame.answerDecision(reqId,picks,value)` 完成 future,方法把 picks/value 映射回真正的
Java 对象返回。**线程/死锁**:`future.get()` 不在任何锁内调用,完成方也不持锁,`pushState()` 的
`synchronized` 与之无交叉,故无死锁。

**已接成真实往返的方法**(每个都有"answer==null → 安全默认"兜底,保证无头能跑):
`confirm`、`showConfirmDialog`、`showOptionDialog`、`showInputDialog`、`getChoices`
(覆盖 `one/oneOrNone/many`)、`chooseSingleEntityForEffect`、`chooseEntitiesForEffect`、
`getAbilityToPlay`(选模式/技能,label 取 `SpellAbilityView.getDescription()`)、
`assignGenericAmount`(多目标分配数量,value=各目标数量的 CSV)。

**决策帧协议**(推送 JSON 里的 `decision`):
```
decision: { reqId, type, title, prompt, min, max, optional, numeric,
            amount?,                       // 仅 type=="amount"
            value?,                        // 仅 type=="input" 的初值
            options:[{ idx, label, cardId?, img? }] }   // 卡类选项带 cardId/img
```
`type` ∈ `confirm | option | ability | input | amount | choose | chooseEntity | chooseEntities`。
浏览器回传:`{ id:"decide", reqId, picks:[idx...], value:"..." }`(picks 是 options 的 idx 数组;
value 用于 input 的文本/数字与 amount 的 CSV)。

**又接了两个(本轮)**:
- `assignCombatDamage` → `amount` 决策(options=各阻挡者,分配总伤害;复用 amount 面板);默认全塞第一个阻挡者。
- `order` → **逐个单选** `choose` 决策(每次从剩余里选"下一个",复用 choose 面板;按 remaining min/max 控制何时停);
  默认取最上面一张,永不阻塞。`many`(多选 N)也走这条。

**超时兜底**:`awaitDecision` 默认**无限等待**(不打断真人);`WebGuiGame.setDecisionTimeoutMs(ms)`
可设一个较长超时,超时后走安全默认。

`manipulateCardList`(占卜/探究等排序)也接成**逐个单选** `choose` 往返(默认保持原顺序)。

**仍为默认桩(`[WEB-TODO]`,绝不阻塞无头)**:`sideboard` —— 单局 vs AI 用不到(只在 match 换局间触发),保留原套牌。

**无头 SmokeTest**:自动驾驶检测到 `hasPendingDecision()` 就 `answerPendingDefault()`(用 null 完成
future → 各方法走默认),因此**整局能跑到自然 game-over**。启动时的 mulligan/coin-toss 走 Input 框架
(action 协议)由自动过优先权处理。

---

## 5. 关键类

| 类 | 职责 |
|---|---|
| `GameViewSerializer` | GameView → 契约 JSON(纯函数);`resolveImage` 填 id/zh/img/imgen |
| `Json` | 极简 JSON 读写(无第三方依赖):`write` 出契约,`parse` 读 `card_index.json` |
| `CardIndex` | 英文名 → {Scryfall id, 中文名} 单例,从 classpath 的 `card_index.json` 加载 |
| `WebGuiGame extends AbstractGuiGame` | 每局一个实例:状态推送 + `submitAction`(action)+ `awaitDecision/answerDecision`(decision)+ `stop()`(断线清理) |
| `WebGuiBase extends GuiDesktop` | 无头 GuiBase;`getNewGuiGame()` 返回全新 WebGuiGame(每局独立) |
| `MatchBootstrap` | `ensureInitialized()` 全局一次;`startHumanVsAi(gui, deck)` 每局独立开一桌 |
| `DeckParser` | Arena 牌表文本 → Forge `Deck`(用 `DeckRecognizer`);`copyForAi` 造 AI 副本 |
| `WebMatchServer` | org.java_websocket:**每连接一局**;解析 newgame/action/decide;断线清理 |
| `BridgeApp` | main:全局初始化 + 起 WS(不预开局) |
| `SmokeTest` | main:自动应答 + 落盘每步 JSON;`-Dmdc.deck.file=` 可注入牌表 |

### 5.1 每连接独立对局
- `WebMatchServer` 给每个 WS 连接维护一个 `Session{gui}`。连接建立先推一个 lobby 占位帧,**不开局**。
- 收到 `{"id":"newgame","deck":...}` 才开:新建 `WebGuiGame`(sink 只发给本连接)→ `DeckParser.parseArena` →
  `MatchBootstrap.startHumanVsAi(gui, deck)`(独立 `HostedMatch`,跑在各自的 Forge 游戏线程,可并发)。
- action/decide 只喂本连接的 gui;断线 `gui.stop()`(认输 + 解阻塞 + 关输入线程)。`FModel.initialize` 仍全局一次。
- 并发安全:每局用**全新** `LobbyPlayerHuman`(非单例)、独立 Game 对象;卡库 `StaticData` 只读共享。

### 5.2 注入牌组
- `deck` = MTGA/Arena 牌表:`Deck` 段 + `<数量> <英文名>` 行 +(可选)`Sideboard` 段。`DeckRecognizer`(forge-core,无 GUI,permissive)解析,认不出的行跳过并 log。
- **AI 对手** = 人类牌组的副本(`DeckParser.copyForAi`,公平镜像)。无 deck/空 deck → 默认牌组(34 树林 + 26 灰棕熊)。

---

## 6. 前端对接(已接)

`MagicDraftCommunity/mdc-web/battle.html`:
- 连上先收 lobby 帧 → 弹**开战前面板**:文本框(牌表来源优先级 **URL `?deck=`(base64 或 encodeURIComponent)→ `localStorage["mdc_deck"]` → 空**)+ 「用牌组开战」/「快速对战(默认牌组)」两个入口。
- 点开战 → `send({id:"newgame",deck})`(快速对战不带 deck)。断线重连自动用上次牌组重新 `newgame`。
- `actions[]`(pass/play/select)→ `send({id,cardId})`;`decision` 帧 → 决策面板 → `send({id:"decide",reqId,picks,value})`。
- WS 连不上 → 回退 `mock_gameview.json`(离线预览,跳过开战面板)。轮抽页把导出牌表写进 `localStorage["mdc_deck"]` 后打开本页即可。

---

## 7. 部署(胖包 + res)

### 7.1 打胖包
```bash
mvn -q -pl forge-web -am -DskipTests -Dmaven.test.skip=true package
```
产物(maven-shade,主类 `forge.web.BridgeApp`,已把 `card_index.json` 和全部依赖打进去):
```
forge-web/target/forge-web-2.0.15-SNAPSHOT-shaded.jar   (~42MB)
```
> 版本号随 `${revision}` 变;文件名后缀固定 `-shaded`。

### 7.2 运行期需要的 res 目录(卡库/版本数据)
`WebGuiBase.getAssetsDir()` 把资源根定位成 `<assetsDir>/res/`,`RES_DIR = assetsDir + "res/"`。
定位顺序:`-Dmdc.assetsDir=<dir>` 优先;否则从进程工作目录向上找含 `forge-gui/res` 的目录。
**部署建议**:把 jar 与一个 `res/` 目录拷到服务器,用 `-Dmdc.assetsDir` 指到 `res` 的父目录。

**实测可跑一局 Constructed 的最小 res 子集(≈210MB)**——只需这些子目录:
```
cardsfolder/  editions/  tokenscripts/  blockdata/  languages/
lists/  formats/  setlookup/  defaults/  ai/     以及 res 根下的 *.txt
```
其中 `cardsfolder`(≈132M,卡牌脚本)与 `languages`(≈59M,本地化;可只保留 en-US 进一步瘦身)是大头。
> 可选:再加 `deckgendecks/`(≈10M)可消掉启动时几条 `LDA/deckgendecks *.dat` 的**非致命**报错(随机造牌用,固定套牌用不到)。
> **可不带**(与无头对战无关,省 ~250M):`adventure(152M) quest(38M) skins(17M) music(16M) sound draft cube conquest puzzle geneticaidecks effects sealed tutorial fonts`。
> 最省事:直接拷整份 `forge-gui/res`(461M)也行。

### 7.3 启动命令
```bash
# 冒烟(无浏览器,自动应答,跑到 game-over)
java -Dmdc.assetsDir=/path/to/forge-gui/ -cp forge-web-*-shaded.jar forge.web.SmokeTest

# 起 WS 桥(默认 127.0.0.1:8899,放 nginx 后面)
java -Dmdc.assetsDir=/path/to/forge-gui/ -jar forge-web-*-shaded.jar
# 指定 host/port(三选一;优先级:CLI 参数 > -D 属性 > 环境变量 > 默认)
java -Dmdc.assetsDir=/path/… -jar forge-web-*-shaded.jar 0.0.0.0 8899
java -Dmdc.ws.host=0.0.0.0 -Dmdc.ws.port=9000 -Dmdc.assetsDir=/path/… -jar forge-web-*-shaded.jar
MDC_WS_HOST=0.0.0.0 MDC_WS_PORT=9000 java -Dmdc.assetsDir=/path/… -jar forge-web-*-shaded.jar
```
> `/path/to/forge-gui/` 是 `res/` 的父目录(即里面有 `res/cardsfolder` 等)。JDK 17 运行。

---

## 8. 已知缺口 / 下一步

1. **图/中文覆盖率**:靠英文名精确匹配 `card_index.json`;个别异画名/双面背面/异名可能查不到 → 回退 Forge id、`img/zh` 空。DFC 背面图暂用 front 路径。
2. **仍为默认桩的决策**:`sideboard`(单局 vs AI 用不到)。
3. **actions 精确化**:优先权空闲态"可出的牌"依赖 `setSelectables`,实测未必含地牌;可改为直接查引擎可用 SpellAbility 生成 `play`。
4. **AI 牌组**:目前用人类牌组的副本(公平镜像);后续可换成按颜色/主题选一套预设强度更合适的对手。

---

## 9. 验证记录

- `mvn -pl forge-web -am -DskipTests package`:通过,产出 `forge-web-2.0.15-SNAPSHOT-shaded.jar`(~42MB,含 card_index.json + 每连接对局 + 牌组注入)。
- **牌组注入 → game-over**:`SmokeTest -Dmdc.deck.file=<Arena牌表>`,解析 60 张主牌(0 跳过),跑到**自然 game-over**(777 步),对局中只出现注入牌组的卡(Forest/Mountain/Grizzly Bears/Llanowar Elves)。
- **并发/独立**:`BridgeApp` 起一桥,两个 WS 客户端各发不同 `newgame`(绿:Forest+Llanowar / 红:Mountain+Grizzly Bears)——GREEN 只见 Forest,RED 只见 Mountain/Grizzly Bears,**零串场**,各自双回合推进。
- **`DeckParser`**(独立):Arena 文本 20 Forest+4 Llanowar+4 Bears+2 Growth+Sideboard → main=30/side=3,假名跳过;AI 副本=30。
- **胖包 `java -jar` BridgeApp**:监听可配 host/port;WS 连上收 lobby 帧→newgame→牌桌,手牌卡带中文/卡图。
- 早前:全 res 默认牌组 game-over(~6383 步);最小 res(210M)可跑;中文/图独立验证;决策往返 `confirm`→false / `getChoices`→[C] / `input`→"7";`battle.html` JS `node --check` 通过。
