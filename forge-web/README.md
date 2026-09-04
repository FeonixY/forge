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
| `id` | `CardView.getId()`(Forge 内部 id,字符串) | **不是 Scryfall id**,详见"已知缺口" |
| `name` | `CardStateView.getName()`(英文) | |
| `zh` | `""` | **待补**:引擎无中文名 |
| `tapped` | `CardView.isTapped()` | |
| `types[]` | `CardTypeView` 的 supertypes + coreTypes 的 `name()` | 如 `["Basic","Land"]`、`["Creature"]` |
| `power/toughness` | 仅当 `isCreature()` 时取 `getPower/getToughness`,否则 `""` | 与契约一致 |
| `counters[]` | `GameEntityView.getCounters()`(Multiset) | P1P1→`+1/+1`,M1M1→`-1/-1` |
| `img` | `""` | **待补**:需 Scryfall id 才能拼 mtgch 图 URL |

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

**超时兜底**:`awaitDecision` 默认**无限等待**(不打断真人);`WebGuiGame.setDecisionTimeoutMs(ms)`
可设一个较长超时,超时后走安全默认。

**仍为改进版默认桩(`[WEB-TODO]`,绝不阻塞无头)**:`assignCombatDamage`(全部塞给第一个阻挡者)、
`order`、`sideboard`、`manipulateCardList`。这些需要更专门的 UI,留待后续。

**无头 SmokeTest**:自动驾驶检测到 `hasPendingDecision()` 就 `answerPendingDefault()`(用 null 完成
future → 各方法走默认),因此**整局能跑到自然 game-over**。启动时的 mulligan/coin-toss 走 Input 框架
(action 协议)由自动过优先权处理。

---

## 5. 关键类

| 类 | 职责 |
|---|---|
| `GameViewSerializer` | GameView → 契约 JSON(纯函数) |
| `Json` | 极简 JSON 写出(无第三方依赖) |
| `WebGuiGame extends AbstractGuiGame` | IGuiGame 实现:状态推送 + `submitAction`(action)回喂 + `awaitDecision/answerDecision`(decision 往返) |
| `WebGuiBase extends GuiDesktop` | 无头 GuiBase;`getNewGuiGame()` 返回共享的 WebGuiGame |
| `MatchBootstrap` | 装 GuiBase、`FModel.initialize`、造两副牌、`HostedMatch.startMatch(...)` |
| `WebMatchServer` | org.java_websocket 服务:广播状态 / 解析 action 与 decide 回传 |
| `BridgeApp` | main:起 WS + 开局 |
| `SmokeTest` | main:自动过优先权 + 落盘每步 JSON |

启动序列(见 `HostedMatch.startGame`,本桥用其 `startMatch` 高层入口):
`GuiBase.setInterface(WebGuiBase)` → `FModel.initialize` → 建 `RegisteredPlayer`(human=`GamePlayerUtil.getGuiPlayer()`,ai=`createAiPlayer()`)→ `new HostedMatch().startMatch(Constructed, null, players, humanRp, webGui)`。HostedMatch 内部完成 `setGui/setOriginalGameController/subscribeToEvents(FControlGameEventHandler)/openView`,并在 Forge 游戏线程上 `match.startGame()`(阻塞等输入)。

---

## 6. 前端对接(已接)

`MagicDraftCommunity/mdc-web/battle.html` 已改成 WebSocket 数据源:
- `connect()` → `ws://<location.hostname>:8899`(端口可用 `window.MDC_WS_PORT` 覆盖);
  `onmessage` → `render(JSON.parse(...))`;断线 2s 自动重连;**连不上 1.5s 内回退 `fetch(mock_gameview.json)`**(离线仍可看)。
- `actions[]`(pass/play/select)按钮点击 → `send({id,cardId})`。
- 当帧带 `decision` 时弹**决策面板** `renderDecision(dec)`:confirm/option/ability=按钮;input=输入框;
  choose/chooseEntity/chooseEntities=可多选(卡类选项显示卡图,带 min/max 校验);amount=各目标数字框(校验和=amount)。
  选完 `send({id:"decide",reqId,picks,value})`。

---

## 7. 已知缺口 / 下一步

1. **卡图 + 中文名(最大缺口)**:引擎按"英文名 + set + 收集编号"标识卡,**没有 Scryfall id、没有中文名**。
   现状:`img:""`、`zh:""`、`id`=Forge 内部 id(仅作 key)。待补:桥外用 (setCode, collectorNumber)/英文名查 MDC `set_cards` 表回填。
2. **仍为默认桩的决策**:`assignCombatDamage`(全塞第一个阻挡者)、`order`、`sideboard`、`manipulateCardList`——需专门 UI,后续接。
3. **actions 精确化**:优先权空闲态下"可出的牌"依赖 `setSelectables`,实测未必含地牌;可改为直接查引擎可用 SpellAbility 生成 `play`。
4. **打包上线**:加 maven-shade/assembly 出胖 jar;服务端需带 `forge-gui/res` 卡库数据。

---

## 8. 验证记录

- `mvn -pl forge-game,forge-ai -am compile`:通过。
- `mvn -pl forge-web -am compile`:通过。
- `SmokeTest`(带决策框架):跑到**自然 game-over**(~6388 步),JSONL 逐行合法。
- **决策往返**(独立测试):`confirm`(选"否")→false、`getChoices`(选 idx2)→[C]、`showInputDialog`→"7",均正确;`decision` 帧形状符合协议。
- **实时 WS**:`BridgeApp` 起服务,WS 客户端连上收到 400 帧、双方回合、全阶段;`action`(pass/select)推进引擎;`decide` 消息可解析。
- `battle.html` JS:`node --check` 通过。
