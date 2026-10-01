原 `HomeScreen.kt:238–294` 有31个回调，`ProfileScreen.kt:316–343` 有17个。完整签名、原 AppNavigation 调用语义、候选 Shell 锚点与文件 SHA 在 `nav-map.json`。这是可变候选源的只读观察，不代表实际 Root 已挂载，也没有编译或 GUI 证明。

最可能阻塞完整挂载的15项：

1. 完整 Profile：Shell `USER` 是 UP 主空间；当前 enum/分支没有完整 `DesktopOriginalProfileHost`。`onProfileClick` 不能转 `openUser(currentMid)`。
2. Home 视频请求：保留 `HomeVideoClickRequest` 的 CID、vertical、source/sourceRoute 和原 dynamic redirect。现 `openVideo(VideoCard)` 只提供平面播放参数。
3. Story：原直接竖屏入口设置/方向探测和普通 VideoDetail 分派不能丢弃。空种子 Home Story 按钮是另一条合法路由。
4. 返回几何和 clock：Return38 需要实际 currentKey、ancestor、visible route、root origin、CardPositionManager 点击快照与实际 clock。先捕获/prearm 再挂目的页，不在返回时查当前 feed 或填 Zero/空 key。
5. 返回效果：top-level active、return/quick、consume 原状态必须来自真实路由；覆盖 Home 不退休数据 owner/planner，不能硬编码 false。
6. 账号切换：侧栏开关禁用时原 nullable 合法；启用时需要同 Store 账号对话框。登录完成/退出/切换要重建当前 epoch Home，不能刷新退役 VM，旧 planner 必须先退役。
7. Partition/Category：完整 Partition 与不可变 TID/name Category route；现 REGION/Discovery 分支不能替代。
8. Live 四个子页：Search/Area/Following/AreaDetail 是不同 typed entries。保留 parentAreaId/areaId/title 与返回来源；视频 SEARCH/个人关注/flat LiveBrowser 不能替代。
9. Bangumi：Home 点 season/episode 都进 Detail；Profile `epId>0` 直接 Player。保留两 ID、初始 type 和原分派。
10. 收藏订阅/收藏夹：FavoriteSubscribed 是同 Favorite VM 的指定分类；folder 需要 mediaId/ownerMid/title。现 `openCollection` 会清 title，已有 CommunityNavigation 专用 folder lambda 可复用。
11. Following MID：Profile 原任意传入 MID；Personal 当前账号 FOLLOWINGS 不证明任意用户路由已消费。
12. 个人云端映射：使用 CLOUD_HISTORY/CLOUD_FAVORITES/WATCH_LATER/LIKED/MESSAGES；本地 HISTORY/FAVORITES 是不同服务。
13. Profile 状态：真实 active/page、refresh generation、原 history service policy、skin/budget、reselect channel 必须传入。
14. Root 导航 admission：先真实 checkpoint 接受，再同 Store→entry 的 commitIfCurrent；page 被视频覆盖不能 close Home/plan。
15. embedded/global effects：原 Subscription 的 articleOpen/listState/settings 与 Home platform/media/metric provider 要被实际消费者读取，provider 放到共用消费者祖先。

可直接复用的已有入口：`loginDialog`/AdvancedLoginDialog、SETTINGS/SEARCH、Dynamic route、Story、Space、Downloads、WatchLater、Liked、Messages、云历史/云收藏、Weekly 的现返回快照及 Plugins。Live 房间可复用 `openLive` 的播放 owner，但原 title/uname 初始元数据要补入实际 typed route。Bangumi 可复用同播放 owner，不能把现 browser 挂载等同完整原 Detail/Player。

所有回调外层应捕获 retained Home epoch/Root gate；点击时 `commitIfCurrent` 调用真实导航，不捕获可以在下一 epoch 改写的 URL/route。父持有唯一 Home 工厂/导航安装；本报告未编辑这些源码，Playback84 历史保持不动。
