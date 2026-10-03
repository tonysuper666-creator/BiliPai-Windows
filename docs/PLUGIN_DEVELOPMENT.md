# 🔌 BiliPai 插件开发指南

本文档面向想要为 BiliPai 创建自定义插件的开发者。BiliPai 提供了一个灵活的插件系统，当前主要支持四种开发路径：

| 类型 | 难度 | 适用场景 |
|------|------|----------|
| **JSON 规则插件** | ⭐ 简单 | 内容过滤、弹幕净化、关键词屏蔽 |
| **JS 插件（`.bilipai.js`）** | ⭐⭐ 中等 | 内容源聚合：直播源、影视目录、网页抓取 |
| **外部 `.bpskin` 皮肤包** | ⭐ 预览 | 首页、底栏、个人页背景及部分动效 |
| **外部 `.bpplugin` Kotlin 包** | ⭐⭐ 预览 | 推荐算法、播放器/弹幕接口适配、能力授权流程验证 |
| **源码级原生 Kotlin 插件** | ⭐⭐⭐ 进阶 | 复杂功能、API 集成、自定义 UI、立即运行的深度集成 |

> [!CAUTION]
> 当前仓库注册了 10 个内置插件，并支持通过 URL 导入外部 JSON 规则插件；但插件生态仍处于早期阶段。
> `plugins/community/` 目前仅包含 1 个演示插件，社区规模和兼容性样本都还有限。
> 外部 `.bpplugin` Kotlin 包当前支持预览、签名/哈希展示和能力授权记录，宿主尚不执行外部 Dex。
> 外部 `.bpskin` 皮肤包是数据型资源包，只能提供资源、颜色和适用界面声明，不能替换 Compose 组件或执行代码。
> 引入第三方插件前请自行审阅规则内容、验证兼容性，并假设规则能力与导入体验会继续随版本迭代。

---

## 📋 目录

- [JSON 规则插件（推荐入门）](#-json-规则插件推荐入门)
  - [快速开始](#快速开始)
  - [插件结构](#插件结构)
  - [字段参考](#字段参考)
  - [操作符大全](#操作符大全)
  - [示例插件](#示例插件)
- [JS 插件（`.bilipai.js`）](#-js-插件-bilipaijs)
  - [插件结构](#插件结构-1)
  - [宿主 API](#宿主-api)
  - [参数类型](#参数类型)
  - [详情导航（link + loadDetail）](#详情导航link--loaddetail)
  - [布局预设（bplayout）](#布局预设bplayout)
  - [第三方弹幕（danmakuFunctionName）](#第三方弹幕danmakufunctionname)
  - [媒体条目模型](#媒体条目模型)
  - [调试](#调试)
- [外部 `.bpplugin` Kotlin 包（预览）](#-外部-bpplugin-kotlin-包预览)
- [外部 `.bpskin` 皮肤包（预览）](#-外部-bpskin-皮肤包预览)
- [源码级原生 Kotlin 插件](#-源码级原生-kotlin-插件)
- [安装与分发](#-安装与分发)
- [常见问题](#-常见问题)

---

## 📝 JSON 规则插件（推荐入门）

JSON 规则插件是最简单的插件形式，只需编写一个 JSON 文件即可实现内容过滤功能。无需编程基础！

### 快速开始

1. 创建一个 `.json` 文件
2. 按照下面的格式编写规则
3. 上传到任意公开可访问的 URL（如 GitHub Gist、Cloudflare R2）
4. 在 BiliPai 中通过 **设置 → 插件中心 → 导入外部插件** 安装

### 插件结构

```json
{
    "id": "my_plugin",           // 唯一标识符（英文、下划线）
    "name": "我的插件",           // 显示名称
    "description": "插件描述",    // 简短描述
    "version": "1.0.0",          // 版本号
    "author": "你的名字",         // 作者
    "type": "feed",              // 插件类型: "feed" 或 "danmaku"
    "rules": [                   // 规则数组
        {
            "field": "title",    // 匹配字段
            "op": "contains",    // 操作符
            "value": "广告",      // 匹配值
            "action": "hide"     // 动作
        }
    ]
}
```

### 字段参考

#### Feed 插件（推荐流过滤）

| 字段 | 说明 | 示例值 |
|------|------|--------|
| `title` | 视频标题 | `"震惊"` |
| `duration` | 视频时长（秒） | `60` |
| `owner.mid` | UP 主 UID | `12345678` |
| `owner.name` | UP 主名称 | `"某UP主"` |
| `stat.view` | 播放量 | `100000` |
| `stat.like` | 点赞数 | `5000` |

#### Danmaku 插件（弹幕过滤）

| 字段 | 说明 | 示例值 |
|------|------|--------|
| `content` | 弹幕内容 | `"666"` |
| `userId` | 发送者 UID | `12345678` |
| `type` | 弹幕类型 | `1` |

### 操作符大全

| 操作符 | 说明 | 示例 |
|--------|------|------|
| `eq` | 等于 | `"op": "eq", "value": 60` |
| `ne` | 不等于 | `"op": "ne", "value": 0` |
| `lt` | 小于 | `"op": "lt", "value": 60` |
| `le` | 小于等于 | `"op": "le", "value": 60` |
| `gt` | 大于 | `"op": "gt", "value": 100000` |
| `ge` | 大于等于 | `"op": "ge", "value": 100000` |
| `contains` | 包含 | `"op": "contains", "value": "广告"` |
| `startsWith` | 以...开头 | `"op": "startsWith", "value": "【"` |
| `endsWith` | 以...结尾 | `"op": "endsWith", "value": "】"` |
| `regex` | 正则匹配 | `"op": "regex", "value": "^[哈]{5,}$"` |
| `in` | 在列表中 | `"op": "in", "value": [123, 456]` |

### 动作类型

| 动作 | 说明 | 可选参数 |
|------|------|----------|
| `hide` | 隐藏匹配内容 | 无 |
| `highlight` | 高亮显示（仅弹幕） | `style` 对象 |

#### 高亮样式

```json
{
    "action": "highlight",
    "style": {
        "color": "#FFD700",    // 十六进制颜色
        "bold": true,          // 粗体
        "scale": 1.2           // 缩放比例
    }
}
```

### 🆕 复合条件（AND/OR）

当前版本支持使用 `and` 和 `or` 组合多个条件实现更精确的过滤。

#### AND 条件

所有子条件**都必须满足**时才触发动作：

```json
{
    "condition": {
        "and": [
            { "field": "duration", "op": "lt", "value": 60 },
            { "field": "title", "op": "contains", "value": "搬运" }
        ]
    },
    "action": "hide"
}
```

#### OR 条件

**任一**子条件满足时即触发动作：

```json
{
    "condition": {
        "or": [
            { "field": "owner.name", "op": "contains", "value": "营销号" },
            { "field": "title", "op": "regex", "value": "震惊.*必看" }
        ]
    },
    "action": "hide"
}
```

#### 嵌套条件

支持 AND/OR 嵌套实现复杂逻辑：

```json
{
    "condition": {
        "and": [
            { "field": "stat.view", "op": "lt", "value": 1000 },
            {
                "or": [
                    { "field": "title", "op": "contains", "value": "广告" },
                    { "field": "title", "op": "contains", "value": "推广" }
                ]
            }
        ]
    },
    "action": "hide"
}
```

> 💡 **向后兼容**：旧格式 `field/op/value` 仍然有效，无需修改现有插件。

### 示例插件

#### 1️⃣ 短视频过滤器

过滤时长小于 60 秒的短视频：

```json
{
    "id": "short_video_filter",
    "name": "短视频过滤",
    "description": "隐藏时长小于60秒的视频",
    "version": "1.0.0",
    "author": "BiliPai",
    "type": "feed",
    "rules": [
        {
            "field": "duration",
            "op": "lt",
            "value": 60,
            "action": "hide"
        }
    ]
}
```

#### 2️⃣ 标题关键词过滤

过滤标题党视频：

```json
{
    "id": "keyword_filter",
    "name": "标题关键词过滤",
    "description": "过滤包含指定关键词的视频",
    "version": "1.0.0",
    "author": "BiliPai",
    "type": "feed",
    "rules": [
        {
            "field": "title",
            "op": "contains",
            "value": "广告",
            "action": "hide"
        },
        {
            "field": "title",
            "op": "regex",
            "value": "震惊.*必看",
            "action": "hide"
        }
    ]
}
```

#### 3️⃣ 弹幕净化器

过滤刷屏弹幕，高亮同传翻译：

```json
{
    "id": "danmaku_cleaner",
    "name": "弹幕净化",
    "description": "过滤刷屏弹幕，高亮同传翻译",
    "version": "1.0.0",
    "author": "BiliPai",
    "type": "danmaku",
    "rules": [
        {
            "field": "content",
            "op": "regex",
            "value": "^[哈]{5,}$",
            "action": "hide"
        },
        {
            "field": "content",
            "op": "startsWith",
            "value": "【",
            "action": "highlight",
            "style": {
                "color": "#FFD700",
                "bold": true
            }
        }
    ]
}
```

---

## 🟨 JS 插件（`.bilipai.js`）

JS 插件是运行在宿主 WebView 沙箱中的单文件脚本，适合做内容源聚合：电视直播、在线影视目录、榜单抓取等。脚本声明插件元数据和若干**模块**，每个模块对应一个异步函数，返回媒体条目数组，宿主负责渲染列表、驱动分页和调用播放器。

参考实现：[`examples/plugins/tv-live.bilipai.js`](../examples/plugins/tv-live.bilipai.js)（电视直播源解析，演示多线路播放与图标回退）与 [`examples/plugins/bangumi-catalog.bilipai.js`](../examples/plugins/bangumi-catalog.bilipai.js)（影视目录，演示网格布局、分页、详情导航；数据源为豆瓣移动端公开接口，演示 HTTP 自定义 Referer/UA 头）。选数据源时注意：接口必须在用户网络环境直连可达，且部分站点（如 api.bilibili.com 的列表端点）有风控会返回 HTML 而非 JSON。用脚手架生成骨架：

```bash
node plugins/tools/create-plugin.js dev.example.my_plugin ./my-plugin
```

### 插件结构

```js
window.BiliPaiPlugin = {
  id: 'dev.example.my_plugin',   // ^[A-Za-z0-9_.-]{1,64}$
  title: '我的插件',
  version: '1.0.0',
  author: '',
  description: '一句话描述插件提供什么内容。',
  // 权限按需声明，安装时会展示给用户确认：
  permissions: ['NETWORK', 'PLUGIN_STORAGE', 'EXTERNAL_MEDIA_PLAYBACK'],
  // 可选：详情导航。声明后宿主在用户点开带 link 的条目时以 {"link": ...} 调用该函数。
  detailFunctionName: 'loadDetail',
  detailCacheDuration: 60,     // 详情缓存秒数，默认 60；0 不缓存
  // 可选：要求的宿主 API 版本，默认 1；当前宿主最高 2（2 = dom/分页/缓存/详情导航）
  // requiredApi: 2,
  modules: [
    {
      id: 'main',
      title: '内容列表',
      description: '',
      functionName: 'loadItems',
      cacheDuration: 600,        // 可选：结果缓存秒数；0 或缺省不缓存
      layout: 'grid',            // 可选：grid = 海报网格，list = 单列列表（默认）
      params: [
        { name: 'sourceUrl', title: '数据源 URL', type: 'text', defaultValue: '' },
        { name: 'category', title: '分类', type: 'enum', defaultValue: 'all',
          options: [{ title: '全部', value: 'all' }] },
        { name: 'page', title: '页码', type: 'page' }   // 宿主驱动，不显示在表单
      ]
    }
  ],
  loadItems: loadItems,
  loadDetail: loadDetail
};

async function loadDetail(params) {
  // params.link 就是条目上的 link 字段原样回传
  const response = BiliPai.http.get(String(params.link || ''));
  if (response.code < 200 || response.code >= 300) {
    throw new Error('详情请求失败: HTTP ' + response.code);
  }
  return [/* 新一层的媒体条目 */];
}

async function loadItems(params) {
  const response = BiliPai.http.get(String(params.sourceUrl || ''));
  if (response.code < 200 || response.code >= 300) {
    throw new Error('数据源请求失败: HTTP ' + response.code);
  }
  return [/* BiliPaiJsMediaItem 数组，见下文 */];
}
```

### 宿主 API

| API | 说明 |
| --- | --- |
| `BiliPai.http.get(url, headers?)` | 发起 GET，返回 `{ code, body, headers }` |
| `BiliPai.http.post(url, body, headers?)` | 发起 POST（body 为字符串，默认 JSON 类型），返回同上 |
| `BiliPai.dom.parse(html)` | 解析 HTML，返回文档包装节点 |
| `BiliPai.storage.get(key)` / `set(key, value)` / `remove(key)` | 插件私有持久化（值为字符串） |
| `BiliPai.log(message)` | 输出到 logcat（tag `BiliPaiJsPlugin`） |

要点：

- **HTTP headers**：`headers` 是普通对象；传入 `User-Agent` / `Referer` 会被尊重，不传 UA 时宿主注入默认值。抓站通常需要这两个头。
- **DOM 解析**：`BiliPai.dom.parse(html)` 基于浏览器 `DOMParser`，支持完整 CSS 选择器。节点包装对象提供 `text`（去空白文本）、`html`（innerHTML）、`attr(name)`、`select(selector)`（数组）、`selectOne(selector)`（可空）；文档对象额外有 `title` 和 `body`。
- **无浏览器网络**：WebView 内不能 `fetch`/XHR，所有请求必须走 `BiliPai.http`；也没有 `localStorage`，用 `BiliPai.storage`。
- **执行模型**：每次调用新建 WebView、跑完即毁，模块级变量不跨调用存活；单次调用 15 秒超时。返回值可以是 Promise，reject 的 `message` 会原样展示给用户。
- **存储**：值必须是字符串，对象先 `JSON.stringify`。

### 参数类型

| 类型 | 表单表现 | 传给函数的值 |
| --- | --- | --- |
| `text` | 文本输入框 | 字符串 |
| `enum` | 选项 chips（切换即重新加载） | 字符串 |
| `count` | 带 options 时为 chips | 字符串 |
| `page` | **不显示**，宿主滚动到底自动递增 | 数字，从 1 开始 |
| `offset` | **不显示**，宿主传入已加载条数 | 数字 |

声明了 `page` 或 `offset` 参数的模块自动获得无限滚动：宿主在用户滚到底时用递增的 `page`/`offset` 重新调用函数，函数**只返回新的一页**（宿主负责拼接）；返回空数组即停止分页。

### 详情导航（link + loadDetail）

声明 `detailFunctionName` 后，插件就是一个**可导航的小站点**：

- 条目带 `link` 字段（任意 opaque 字符串，通常是详情页 URL 或内部 ID）且不可播放时，内容页显示「打开」；点击后宿主以 `{"link": ...}` 调用详情函数，把返回的条目渲染成新一层列表，并提供「返回上一层」。
- 详情函数返回的条目同样可以再带 `link`，层层深入（分类 → 列表 → 详情 → 剧集）。
- 详情结果按 `detailCacheDuration`（秒）缓存，key 为 link。
- 可播放条目点击仍是播放，不受影响。

典型用法：详情页返回剧集列表（`childItems` 或平铺）、相关推荐（带 link 可继续深入）、分类/演员（相当于 Forward 的 genreItems/peoples，用带 link 的条目表达即可）。

### 布局预设（`.bplayout`）

`.bplayout` 是一个 JSON 文件，分享某个插件模块的「参数 + 布局」组合，让其他用户一键获得相同的浏览体验：

```json
{
  "formatVersion": 1,
  "name": "番剧索引 · 排名网格",
  "pluginId": "bilipai.official.bangumi",
  "moduleId": "browse",
  "layout": "grid",
  "params": { "subjectType": "2", "sort": "rank" }
}
```

- **导出**：插件内容页 → 「分享布局（.bplayout）」，以文本分享当前模块的参数和布局。
- **导入**：插件中心 → JS 插件 → 「导入布局」，选择 `.bplayout` 文件。宿主校验插件与模块是否已安装，缺失时会提示先安装对应 `.bilipai.js`（与 Forward 的"本地导入缺失"行为一致）。
- **生效**：导入后打开对应插件模块即自动套用布局与参数；用户自己改过的参数值优先于预设。

官方示例见 [`examples/plugins/bangumi-catalog.bplayout`](../examples/plugins/bangumi-catalog.bplayout)。

### 第三方弹幕（danmakuFunctionName）

声明 `danmakuFunctionName` 并申请 `DANMAKU_STREAM` 权限后，插件可以为**外部播放器**播放的条目提供第三方弹幕（dandanplay 风格）：

- 用户播放条目时，宿主以 `{"title": "<条目标题>"}` 调用弹幕函数。
- 返回 `[ { timeMs, text, mode, color } ]`：`timeMs` 毫秒时刻、`mode` 取 `scroll` / `top` / `bottom`（缺省滚动）、`color` 支持 `#RRGGBB` / `#AARRGGBB` / 十进制整数（缺省宿主默认色）。
- 结果按 `danmakuCacheDuration`（秒，默认 300）按标题缓存；直播源没有固定时间轴，返回空数组即可。
- 播放器底栏出现「弹幕」开关；未授予 `DANMAKU_STREAM` 时宿主不会调用弹幕函数。

```js
window.BiliPaiPlugin = {
  // ...
  permissions: ['NETWORK', 'PLUGIN_STORAGE', 'EXTERNAL_MEDIA_PLAYBACK', 'DANMAKU_STREAM'],
  danmakuFunctionName: 'loadDanmu',
  loadDanmu: loadDanmu
};

async function loadDanmu(params) {
  const matched = searchDanmuByTitle(String(params.title || '')); // TODO: 你的弹幕源匹配逻辑
  if (!matched) return [];
  const response = BiliPai.http.get(matched.commentUrl);
  if (response.code < 200 || response.code >= 300) return [];
  return JSON.parse(response.body).map(function (c) {
    return { timeMs: c.t * 1000, text: c.m, mode: 'scroll' };
  });
}
```

### 媒体条目模型

```js
{
  id: 'unique-stable-id',       // 列表 key，必须唯一
  title: '标题',
  description: '',
  // 图片回退链（宿主按展示位置自动挑选）：
  coverUrl: '', backdropUrl: '', posterPath: '',
  coverUrls: [], backdropUrls: [], backdropPaths: [], posterPaths: [],
  type: 'video',
  // 三选一：
  videoUrl: 'https://.../index.m3u8',                       // 单直链
  streams: [{ id: 'line-1', title: '线路 1', url: '...',   // 多线路
              headers: { Referer: 'https://...' } }],
  childItems: [/* 同结构，用于分类/剧集嵌套 */],
  link: 'detail-or-category-anchor'   // 详情导航入口，见上文
}
```

不可播放的条目仍会渲染（用于分类/文件夹，点击展开 `childItems`）。

### 调试

- 插件中心 → 已安装 JS 插件 → 「测试」：用默认参数运行任意模块，显示条目数、耗时、前几条标题或错误信息。
- 参数改动在插件内容页即时生效；`BiliPai.log` 输出可用 `adb logcat -s BiliPaiJsPlugin` 查看。
- 错误消息会原样展示在内容页，写给用户看（中文）。
- 重新导入同 ID 的脚本即更新插件（需要重新确认授权）。

---

## 📦 外部 `.bpplugin` Kotlin 包（预览）

外部 `.bpplugin` 是面向未来插件生态的 Kotlin 包格式。完整的 SDK 接口选择、`plugin-manifest.json` 与能力声明、推荐插件最小示例、打包与构建命令见 [Plugin SDK 指南](../plugins/sdk/README.md)。可打包预览的示例见 [Today Watch Remix](../plugins/samples/today-watch-remix/) 与[观感罗盘](../plugins/samples/watch-compass/)。

> [!IMPORTANT]
> 当前宿主只解析、签名校验、展示和能力授权记录 `.bpplugin`，不会执行外部 Dex。如果你需要插件立即影响应用行为，请使用[源码级原生 Kotlin 插件](#-源码级原生-kotlin-插件)。

---

## 🎨 外部 `.bpskin` 皮肤包（预览）

`.bpskin` 是数据型 ZIP 资源包，支持首页、底栏、个人页背景及部分动效，不执行代码。

完整说明见 [BPSkin 开发规范](BPSKIN_DEVELOPMENT.md)：包结构、manifest 字段、界面与资源、颜色与动效、导入方式和校验限制。

插件中心支持 `.bpskin`、主题目录 ZIP、内层 `_package.zip` 和受支持的主题／装扮 JSON。完整本地包可离线导入；JSON 引用的远程资源需要下载。预览后点击“保存并启用”。

示例：[冬日云朵](../plugins/samples/winter-cloud-skin/README.md)、[蓝雪女仆](../plugins/samples/blue-snow-maid-skin/README.md)。

---

## 🔧 源码级原生 Kotlin 插件

> ⚠️ 原生插件需要修改源码并重新编译，适合有 Android 开发经验的开发者

完整的 `Plugin` / `PlayerPlugin` / `FeedPlugin` / `DanmakuPlugin` 接口签名、`SkipAction` 与数据结构、配置持久化、UI 开发、调试与最佳实践见[原生插件开发指南](NATIVE_PLUGIN_DEVELOPMENT.md)。示例：[SponsorBlockPlugin](../app/src/main/java/com/android/purebilibili/feature/plugin/SponsorBlockPlugin.kt)。

在 `PureApplication.kt` 的插件初始化区域注册：

```kotlin
private fun initPluginStackNow() {
    PluginManager.initialize(this)
    PluginManager.register(YourCustomPlugin())
}
```

---

## 📤 安装与分发

### JSON 插件分发

1. **GitHub Gist** - 创建一个公开的 Gist，使用 Raw 链接
2. **GitHub 仓库** - 放在仓库中，使用 Raw 文件链接
3. **Cloudflare R2 / S3** - 上传到云存储
4. **个人服务器** - 确保 HTTPS 可访问

### 链接格式要求

- 必须是 http/https 直接下载链接（内容为 JSON，`.json` 或 `.bp` 后缀均可）
- 建议使用 HTTPS

---

## ❓ 常见问题

**Q: 插件安装后为什么没生效？**

A: 确保插件已启用（开关为开），部分插件需要重启应用。

**Q: 如何调试我的 JSON 插件？**

A: 使用在线 JSON 验证器检查语法，确保所有字段都正确。

**Q: 正则表达式不生效？**

A: 确保正则表达式语法正确，可以在 [regex101](https://regex101.com/) 上测试。

**Q: 可以组合多个条件吗？**

A: ✅ 支持！使用 `and` 和 `or` 复合条件可以组合多个条件。参见上方的[复合条件（AND/OR）](#-复合条件andor)章节。

---

## 🤝 社区插件

欢迎分享你的插件！提交 PR 到本仓库的 `plugins/community/` 目录。

---

<p align="center">
  <sub>Made with ❤️ by BiliPai Team</sub>
</p>
