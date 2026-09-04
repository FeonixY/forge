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

桥的核心:把引擎的阻塞式"问玩家"调用变成"推状态 + 等浏览器动作"。

**已接(经 Input 框架,真实可用)——优先权 / 攻击 / 阻挡:**
引擎在 `InputPassPriority/InputAttack/InputBlock` 里通过
`showPromptMessage` + `updateButtons` + `setSelectables` 呈现提示,
浏览器回传后由 `WebGuiGame.submitAction(id,cardId)` 映射到 `IGameController`:
- `pass`/`ok` → `selectButtonOk()`(过优先权 / 确认攻防)
- `cancel`/`endturn` → `selectButtonCancel()`
- `play`/`select` + cardId → `selectCard(cardView,null,null)`(使用牌 / 选卡 / 宣告攻击者 / 选阻挡)
- `concede` → `concede()`

**暂为自动桩(`[WEB-TODO]`,让整局能无头跑通,尚未接浏览器)——其余弹窗式决策:**
`getChoices` / `confirm` / `showConfirmDialog` / `showOptionDialog` / `showInputDialog` /
`chooseSingleEntityForEffect` / `chooseEntitiesForEffect` / `assignCombatDamage` /
`assignGenericAmount` / `getAbilityToPlay` / `order` / `sideboard` / `manipulateCardList`。
每次触发都会在 stderr 打 `[WEB-TODO] ...`,便于按需逐个接成真实浏览器提示。

启动时的"是否调度手牌(mulligan)"走 `confirm`,桩返回 `defaultIsYes`=保留,故不会 mulligan、不触发 London 置底。

---

## 5. 关键类

| 类 | 职责 |
|---|---|
| `GameViewSerializer` | GameView → 契约 JSON(纯函数) |
| `Json` | 极简 JSON 写出(无第三方依赖) |
| `WebGuiGame extends AbstractGuiGame` | IGuiGame 实现:状态推送 + `submitAction` 回喂 + 决策桩 |
| `WebGuiBase extends GuiDesktop` | 无头 GuiBase;`getNewGuiGame()` 返回共享的 WebGuiGame |
| `MatchBootstrap` | 装 GuiBase、`FModel.initialize`、造两副牌、`HostedMatch.startMatch(...)` |
| `WebMatchServer` | org.java_websocket 服务:广播状态 / 解析回传动作 |
| `BridgeApp` | main:起 WS + 开局 |
| `SmokeTest` | main:自动过优先权 + 落盘每步 JSON |

启动序列(见 `HostedMatch.startGame`,本桥用其 `startMatch` 高层入口):
`GuiBase.setInterface(WebGuiBase)` → `FModel.initialize` → 建 `RegisteredPlayer`(human=`GamePlayerUtil.getGuiPlayer()`,ai=`createAiPlayer()`)→ `new HostedMatch().startMatch(Constructed, null, players, humanRp, webGui)`。HostedMatch 内部完成 `setGui/setOriginalGameController/subscribeToEvents(FControlGameEventHandler)/openView`,并在 Forge 游戏线程上 `match.startGame()`(阻塞等输入)。

---

## 6. 已知缺口 / 下一步

1. **卡图 + 中文名(最大缺口)**:引擎按"英文名 + set + 收集编号"标识卡,**没有 Scryfall id、没有中文名**。
   battle.html 的图 URL(`images.mtgch.com/.../{scryfallId}...`)需要 Scryfall id。
   现状:`img:""`、`zh:""`、`id`=Forge 内部 id(仅作 key)。
   待补:在桥外用 (setCode, collectorNumber) 或英文名去查 MDC `set_cards` 表,解析出 Scryfall id 与中文名后回填 `id/zh/img`。
   `CardStateView.getSetCode()` 可拿 set;收集编号需从 `PaperCard` 侧取(序列化器已注释 TODO 位置)。
2. **弹窗式决策接浏览器**:把 §4 的 `[WEB-TODO]` 桩逐个改成"推一个决策请求 + 阻塞在 per-request future,浏览器答复后 resume"。
   建议给契约加一个 `decision` 帧类型(choices/confirm/assignDamage 等),前端弹层选择。
3. **actions 精确化**:目前"可出的牌"依赖 `setSelectables`。优先权空闲态下 Forge 会不会把可打的牌塞进 selectables 需再核实;
   必要时改为直接查引擎的可用 SpellAbility 列表来生成 `play` 动作。
4. **前端对接**:`battle.html` 现为 `fetch(mock_gameview.json)`。改造:`new WebSocket("ws://host:8899")`,
   `onmessage` → `render(JSON.parse(e.data))`;把按钮 `onclick` 从 alert 改成 `ws.send(JSON.stringify({id,cardId}))`。
5. **打包上线**:加 maven-shade/assembly 出胖 jar;服务端需带 `forge-gui/res` 卡库数据。

---

## 7. 验证记录

- `mvn -pl forge-game,forge-ai -am compile`:通过(headless 引擎可编译)。
- `mvn -pl forge-web -am compile`:见提交时状态。
- `SmokeTest`:见 `smoke_gameview.jsonl` / `smoke_first.json` 产物。
