# ts 缓存实现说明

## 目标
本文说明当前项目中 HLS `ts` 分片缓存的实际实现方式，重点覆盖以下内容：

- 缓存存储位置
- 播放器读取 `m3u8` 与 `ts` 的链路
- `ts` 缓存的生成时机
- 固定 3 个 worker 的预取模型
- 切源、清理、回源的处理方式

对应策略文档见 `docs/cache-policy.md`。

## 总体结构
当前缓存逻辑主要由以下几个组件组成：

- `WiTVApp`
  - 提供全局唯一的 `SimpleCache`
- `PlayerManager`
  - 初始化 ExoPlayer，并组装播放数据源
- `M3u8RewritingDataSource`
  - 读取并修正 `m3u8`
  - 从最新 playlist 中提取当前直播窗口里的 `ts` 分片列表
- `HlsSegmentPrefetcher`
  - 管理 `ts` 缓存读取、预取、清理、切源清空

## 缓存存储位置
`ts` 缓存使用 Media3 的 `SimpleCache`，存储在应用 `cacheDir` 下的磁盘目录中。

- 缓存目录名：`media3-hls-segment-cache`
- 当前实现只缓存短窗口 `ts` 分片，不缓存 `m3u8`

## 播放链路
播放器初始化时，会把两层数据源串起来：

1. 外层是 `M3u8RewritingDataSource`
2. 内层媒体分片读取走 `HlsSegmentPrefetcher.getPlaybackDataSourceFactory()`

简化后的链路如下：

```text
ExoPlayer
  -> PlayerManager
    -> M3u8RewritingDataSource
      -> 读取 m3u8
      -> updateLiveWindow(...)
    -> HlsSegmentPrefetcher.LoggingPlaybackDataSource
      -> open(ts)
      -> 判断是否绕过缓存
         -> 是: 直接走 upstream
         -> 否: 走 CacheDataSource
```

## m3u8 的处理逻辑
`M3u8RewritingDataSource` 的职责不是缓存 `m3u8`，而是：

1. 拉取 playlist 原文
2. 通过 `HlsMediaSequenceFixUtil` 做修正
3. 把修正后的 playlist 重新返回给 HLS 解析器
4. 调用 `HlsSegmentPrefetcher.updateLiveWindow(...)` 更新“当前直播窗口”

这里的“直播窗口”本质上是当前 `m3u8` 中所有可见 `ts` 分片的有序列表。

## ts 的读取逻辑
播放器真正读取 `ts` 分片时，入口在 `HlsSegmentPrefetcher.LoggingPlaybackDataSource.open()`。

处理过程如下：

1. 判断当前请求是否是 `ts`
2. 如果是，则先调用 `onPlaybackSegmentRequested(...)`
3. `onPlaybackSegmentRequested(...)` 会基于当前请求分片计算其在直播窗口中的位置 `X`
4. 然后做 3 件事：
   - 如果当前分片正被预取，则取消该预取任务
   - 清理 `< X` 的旧分片任务和磁盘缓存
   - 重新计算 `X+1`、`X+2`、`X+3` 的预取目标
5. 如果当前分片曾处于未完成预取中，则本次播放直接绕过缓存，走上游下载
6. 否则交给 `CacheDataSource`，命中则读磁盘缓存，未命中则回源

## 缓存生成时机
当前实现中，`ts` 缓存不是在读到 `m3u8` 时立即生成，而是在播放器真正读取某个 `ts` 分片时触发。

也就是说：

- `m3u8` 只负责提供“当前窗口快照”
- 真正的缓存生成触发点是“播放器读到了窗口位置 `X`”

触发后会尝试预取：

- `X+1`
- `X+2`
- `X+3`

如果超出当前窗口上界，则只预取存在的那部分。

## 固定 3 个 worker 的预取模型
当前版本没有使用“线程池 + 排队队列”的通用模型，而是改成了固定 3 个 worker 槽位：

- `worker[0]`
- `worker[1]`
- `worker[2]`

每个 worker 是一个常驻线程，同时最多处理 1 个目标分片。

### worker 的分配原则
当播放器读取位置变为 `X` 时：

1. 先计算新目标集合 `X+1..X+3`
2. 如果某个 worker 当前目标仍在新集合里，则保留
3. 如果某个 worker 当前目标不在新集合里，则清空或替换
4. 新集合中缺失的目标分片会被分配到空闲 worker

这种方式的特点是：

- 同时只维护最多 3 个未来分片的预取任务
- 不额外保存“排队但还没开始”的任务状态
- 逻辑和 `cache-policy.md` 中的业务语义更接近

## 当前分片正在预取时的处理
如果播放器正在请求某个分片，而这个分片恰好已被某个 worker 分配或正在下载：

1. 立即取消该 worker 对应任务
2. 删除该分片可能残留的部分缓存
3. 本次播放直接绕过缓存，走上游重新下载

这是为了保证：

- 播放请求优先
- 不等待未完成的预取任务
- 避免读取到半截或不稳定的缓存内容

## 旧分片清理逻辑
当播放器定位到窗口中的位置 `X` 后，会把 `< X` 的分片视为过期分片。

对于这些旧分片，系统会：

1. 取消 worker 中仍在处理它们的任务
2. 删除对应磁盘缓存

这样可以保证缓存窗口始终尽量贴近当前播放进度，而不是无限堆积旧分片。

## 切源逻辑
当播放器切换到新的播放地址时，会调用 `HlsSegmentPrefetcher.onPlaybackSourceChanged(...)`。

切源时会做以下操作：

1. 增加 `generation`
2. 清空当前直播窗口快照
3. 取消所有 worker 任务
4. 删除当前源已跟踪到的缓存分片

`generation` 的作用是防止旧源的异步结果在切源后继续生效。

## 缓存命中与回源
`ts` 实际读取使用的是 `CacheDataSource`。

因此在不需要强制绕过缓存时：

- 已命中缓存：从磁盘读取
- 未命中缓存：从上游网络读取

项目里还注册了 `CacheDataSource.EventListener`，用于输出：

- 读取了多少缓存字节
- 缓存被忽略的原因

## 日志观察建议
当前 `HlsSegmentPrefetcher` 已经补充了较详细的调试日志，建议重点关注以下几类日志：

- `Playback cursor: X=...`
  - 当前播放器命中的窗口位置
- `Prefetch targets for X=...`
  - 当前轮次的 `X+1..X+3` 目标
- `Evict stale segments before X=...`
  - 当前被清理的旧分片
- `Assign worker[...]`
  - worker 被分配了哪个目标
- `Worker state after reconcile`
  - 当前 3 个 worker 的状态快照
- `Playback open via upstream only`
  - 当前分片因为抢占预取而直接回源
- `Playback cache hit`
  - 实际从磁盘缓存中读到了数据

## 当前实现的边界
当前实现是为直播 HLS 的短窗口 `ts` 预取设计的，默认假设：

- playlist 会持续刷新
- `ts` 分片 URI 可以稳定作为 cache key
- 同一时刻只需要关注当前播放点之后最多 3 个分片

它不是一个通用的大缓存系统，而是一个围绕直播播放时序构建的“小窗口、强时效”缓存机制。
