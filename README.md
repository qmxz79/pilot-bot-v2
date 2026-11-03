# 副驾搭子 v2

语音优先的开车陪伴应用。v2 是对 v1（`qmxz79/pilot-bot`）的**重做**，不是修补：
v1 代码量不小（约 8,900 行 Kotlin），但体验上全线失守——半双工不能打断、
系统 TTS 机械无情感、记忆靠手动填表、3D 头像生硬、导航/定位脆弱，而且作者
只做静态审核、从未编译、从未真机验收。v2 反过来排优先级：**先保可用，再做
像人，最后考虑形象——而形象直接砍掉，换成极简语音波形。**

## v1 教训 → v2 做法

| v1 用户实测症状 | v1 根因 | v2 做法 |
|---|---|---|
| 容易闪退 | 1500 行 god activity + 未编译过的生命周期/TTS/ASR 链路 | MainActivity < 400 行只做组装；逻辑下沉各模块；CrashLog 记录崩溃；所有回调 main 线程化 |
| 没有声音 | 低速 <15km/h 时 L0 全静默门掐掉播报；TTS 不可用只显示文字 | 取消全静默：任何级别都不闭嘴，只改短句/语气；启动自检 + 界面和语音两路明示降级 |
| 定位不准、导航失败 | GPS 原生坐标与高德坐标系混用；key/权限/SDK 失败无反馈 | 坐标转换统一在 provider 边界做且仅一次；key/权限/GPS 自检返回人类可读状态；SDK 失败自动降级系统 GPS；系统 GPS 链路过滤网络定位（GPS 失联 30s 后且精度≤500m 才用）、无速度时沿用上次速度并平滑、地址反查先换回 WGS-84（2026-10-28 真机：位置偏几百米、速度归零乱跳） |
| 声音机械卡顿 | 系统 TTS + 逐句朗读 + 音频焦点互抢 | 句子级流式队列 + transient 独占音频焦点 + 讯飞云语音已接入（设置页切换） |
| 聊天不自然 | 半双工，不能打断 | 说话期间 AudioRecord 轻量 VAD 检测人声 → 打断 TTS → 切 ASR；另有红色打断按钮 |
| 人设混乱 | 人设只有名字/语气/口头禅几个词 | 固定身份卡（身份、性格、口头禅、禁忌、示例对话），每轮提示词重新锚定 |
| 记不住你 | 记忆是手动填表 | 对话自动抽记忆（喜欢/讨厌/每周/家住/常走等句式）→ 本地 JSON 存储 → 关键词检索注入提示词 |
| 3D 头像生硬、很小 | 贴图状态切换 + 小布局 + 开车看屏 | 砍掉：Canvas 绘制的极简语音波形，无 GL、无模型文件 |

## 架构（每个文件职责单一）

```
app/src/main/java/com/qmxz/pilotbotv2/
├── MainActivity.kt            # 组装 + 生命周期收敛（<400 行）
├── CopilotViewModel.kt        # 唯一编排者：对话轮次、场景、记忆、自检
├── voice/
│   ├── BargeInVoicePipeline.kt# 核心：句子级 TTS 队列 + VAD 打断 + ASR 切换
│   ├── TtsProvider.kt / SystemTtsProvider.kt   # 系统 TTS（无 key 时的兜底）
│   ├── XfyunTtsProvider.kt    # 讯飞云 TTS：WebSocket + 16k PCM 流式 AudioTrack 播放
│   ├── AsrProvider.kt / SystemAsrProvider.kt   # 系统 ASR（无 key 时的兜底）
│   ├── XfyunAsrProvider.kt    # 讯飞云 ASR：WebSocket + 16k 麦克风流式听写
│   ├── XfyunAuth.kt           # 讯飞 HMAC-SHA256 签名 URL（TTS/ASR 共用）
│   ├── VoiceSettings.kt       # 语音引擎切换 + 讯飞 Key（只存本机）
│   ├── SwitchableProviders.kt # 每轮解析当前引擎，切换无需重启
│   ├── CloudTtsProvider.kt / CloudAsrProvider.kt  # 通用云端接口（扩展点，暂未用）
│   ├── AsrErrorMapper.kt      # 错误码 → 人能看懂的提示
│   ├── AudioFocusManager.kt   # transient 独占音频焦点
│   └── VadDetector.kt         # 说话期间的轻量人声检测
├── persona/
│   ├── PersonaCard.kt / PersonaPresets.kt      # 小搭 + 三套预设
│   └── SystemPromptBuilder.kt # 每轮锚定人设 + 记忆 + 场景 + 车速分级
├── memory/
│   ├── MemoryStore.kt         # filesDir 内 JSON，线程安全，容量上限 200
│   ├── MemoryExtractor.kt     # 规则抽取（实时）+ LLM 抽取（每轮回复后，低温度，静默失败）
│   └── MemoryRetriever.kt     # 关键词相关度 top-k
├── safety/
│   ├── DrivingLoadEstimatorV2.kt # 车速/场景只改回复长度和语气，永不静默
│   └── SceneAutoDetector.kt     # M3 安全自动触发：疲劳/深夜/拥堵纯逻辑检测
├── nav/
│   ├── CoordinateTransform.kt # WGS-84 ↔ GCJ-02（防国内 300–500 米偏移）
│   ├── NavigationProvider.kt  # 定位接口 + NavLocation/NavStatus
│   ├── AmapNavigationProvider.kt # 高德定位 SDK（反射隔离，无二进制依赖）
│   ├── SystemGpsFallbackProvider.kt # LocationManager + Geocoder 兜底
│   ├── NaviSessionCoordinator.kt   # 选路 + 失败切换
│   ├── AmapNavLauncher.kt     # 跳转高德 App 导航：URI intent + HTTP 地理编码，无 SDK
│   └── AmapNavListener.kt     # 读高德导航通知：下一指令/剩余距离/结束（关键词容错解析）
├── service/
│   └── CompanionService.kt    # 前台保活：高德在前台时语音不断（话筒+定位类型）
├── llm/
│   ├── LlmClient.kt           # OpenAI 兼容、OkHttp SSE 流式
│   ├── LlmSettings.kt         # SharedPreferences（Key 只存本机）
│   └── SentenceSplitter.kt    # 句子切分 + 朗读净化 + 增量切分器
├── ui/
│   ├── WaveformView.kt        # Canvas 波形（说话/聆听/闲置三态）
│   └── TranscriptAdapter.kt   # 对话记录（无 RecyclerView 依赖）
└── diagnostics/
    ├── CrashLog.kt            # 未捕获异常写文件（可分享，不自动上传）
    └── HealthCheck.kt         # 启动自检：TTS / ASR / GPS / LLM + 语音播报摘要
```

依赖（刻意极简）：AndroidX core / appcompat / material /
lifecycle-viewmodel-ktx / lifecycle-runtime-ktx、kotlinx-coroutines-android、
OkHttp。**没有任何 3D/GL/地图二进制依赖进仓库。**

注意：对话记录没有用 RecyclerView——记录是追加式且短命，LinearLayout
渲染即可，这样可以少一个依赖；见 `TranscriptAdapter` 注释。

## 构建步骤

1. Android Studio 打开本目录（AGP 8.2.2、Kotlin 1.9.22、JDK 17、minSdk 26、targetSdk 34）。
2. 高德：导航不再需要任何 SDK（2026-11-03 起走高德 App），但地名解析
   仍需 Key；定位 SDK 仍是可选（不配自动系统 GPS）：
   - 在项目根目录 `local.properties` 写 `AMAP_KEY=你的高德Key`（该文件已 gitignore，绝不提交）；
   - 如需高德定位（可选）：运行 `scripts/fetch-amap-sdk.sh`，按提示从高德
     官网下载定位 SDK，把 `.aar` 放到 `app/libs/`，并在 `app/build.gradle`
     的 dependencies 里加一行（用真实文件名）：
     `implementation(files("libs/amap-location.aar"))`；
   - 手机上须安装高德地图 App，并给副驾搭子开通知使用权（首次导航时会提示）。
3. LLM Key（必需，否则对话会明示“未配置”）：装好 App 后点「设置」，填
   OpenAI 兼容地址、Key、模型名（默认 DeepSeek 地址与模型），再选人设。
4. 讯飞云语音（无谷歌语音服务的手机必需，否则语音不可用）：在讯飞开放平台
   申请应用，把 AppID、API Key、API Secret 填进「设置」→ 语音引擎选「讯飞
   云语音」（发音人默认 xiaoyan，可改）。Key 只存本机；不选或未填时自动
   回落系统语音。切换立即对下一轮对话生效，无需重启 App。
5. Sync + Run 到真机。

## 真机验收清单（v2 的硬性门槛，v1 从未做过）

- [ ] 车内噪声：60km/h 开窗/开空调下说话，打断检测不误触发、不漏触发
- [ ] 打断：它说话时插话，1 句内停下并切到聆听；打断按钮同样灵
- [ ] 弱网：断网/弱网时对话失败有语音道歉 + 文字详情，不静默、不闪退
- [ ] 长会话：连续对话 30 分钟，人设不漂移，记忆能被下一轮用上
- [ ] 连续 1 小时不闪退：跑满 1 小时行车/模拟，事后查 `crash.log`（应用私有目录）
- [ ] 导航降级：拔掉高德 Key 或删 AAR，App 自动用系统 GPS 且界面明示来源
- [ ] 无声排查：关掉系统语音数据/拒录音权限，App 明示降级且文字输入仍可用
- [ ] 安全自动触发：22 点后开车自动切深夜并开口；慢行 90 秒自动切堵车；连续驾驶 2 小时自动提醒疲劳；手动选场景后 30 分钟内不自动打扰；切后台时不自动开口
- [ ] 高德导航：点「高德导航」输入目的地，跳进高德 App 开始导航；路线行实时显示高德通知的下一指令与剩余距离；高德导航结束时 App 内提示结束
- [ ] 语音导航：说“导航到市政府”，副驾在当前位置 50 公里内搜最近的同名地点、报出全名、拉起高德开始导航，全程不用手点；无 Key、无定位、找不到时明说原因，不猜错城市
- [ ] 通知授权：首次导航时未开通知使用权会提示并打开系统设置，开通后才有导航进度
- [ ] 后台陪伴：高德在前台时副驾搭子语音不断（安全提醒仍能开口）；点通知栏「结束陪伴」或 App 内「结束陪伴」后停止
- [ ] 无高德/无 Key：未装高德 App 或未配 Key 时明示原因、不崩溃（导航不再需要任何 SDK；Key 只用于地名解析）
- [ ] 崩溃日志：设置页「查看崩溃日志」能看到最近崩溃记录，可分享、可清空；无记录时明示暂无
- [ ] 语音避让：高德播报时副驾立刻闭嘴（TTS 停、VAD 停），播完从断句处续说不断句；聆听中被打断则播完自动重听；电话等永久抢占只停不停回

## 已知未验证项（诚实清单，见代码内注释）

- **本地已可编译（2026-11-03 起）**：本机已建 Android SDK 环境，用
  `~/workspace/android-build.sh` 可编出 debug APK（首编已通过）；但每次改动
  仍需本地编一次确认，且 App 内行为仍需真机验证。
- **未真机**：VAD 阈值（2800）、去抖（250ms）、TTS/ASR 行为、权限流程都需真机调。
- **高德反射调用未核对实物 SDK**：方法名按公开的高德定位 SDK API 写（含著名的
  `Hight_Accuracy` 拼写），但手头没有 SDK jar；若签名有出入，provider 会优雅
  降级到系统 GPS，不会崩溃——首次接 SDK 的真机联调需确认。
- **导航已改为高德 App（2026-11-03 决策）**：自研算路、逐条播报、偏离重
  规划已下线（RouteProvider / AmapRouteProvider / NavigationSession 已删），
  不再需要搜索 SDK；定位的高德 SDK 仍是可选（没它自动系统 GPS）。
- **高德通知解析是关键词容错**：通知文案由高德定、版本一变就可能失效，
  解析不到的只显示原文、不猜；只能拿到通知里有的下一指令与剩余距离时间，
  拿不到完整路线；高德语音播报的音频本身无法拦截。
- **语音避让的边界**：避让只在副驾持有音频焦点时生效；若音乐等应用已
  永久持有焦点，副驾是硬说状态，避让收不到回调；高德播报是否走
  transient 焦点需真机验证（若某版本高德不走焦点，避让不触发）。
- **语音导航的零点击取决于高德**：拉起用的是官方 navi intent；8.2.6+
  后 style 参数被高德自身设置忽略，若高德停在路线页仍需点一次开始
  导航；只能启动高德导航，不能语音结束高德导航（没有这个接口）。
- **语音导航只认明确口令**：只认“导航到 / 导航去 / 带我去 / 开车去”
  这类说法，光说“去市政府”和问句（“导航到市政府要多久”）仍走对话；
  “家 / 公司 / 单位”需要先存地址，暂不支持。
- **后台陪伴的边界**：前台服务保的是进程优先级，不是永生；用户把 App
  划掉后语音仍会停，需重开；对话仍需回到 App 里进行，导航期间副驾搭子
  只主动播报安全提醒和导航结束，不监听。
- **讯飞云语音未经真机验证**：TTS/ASR 的 WebSocket 协议按讯飞官方文档实现，
  但本机无 Android SDK、无法编译，真机联调需确认：TTS 每句一次握手带来的
  延迟、ASR 的 pgs 修正与 ls 结束、弱网下的错误提示；无 key 时自动回落系统
  链路，行为与之前一致。
- **记忆检索是关键词级**，非语义向量；LLM 抽取已接线：每轮回复完成后低温度后台抽取，失败静默（规则路径始终兜底）。
- **流式回复的文字记录在整句完成后才上屏**（说话是实时的）；ASR 中间结果暂不显示。
