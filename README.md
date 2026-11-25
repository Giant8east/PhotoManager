# PhotoManager

PhotoManager 是一款面向海量本地目录与跨设备局域网互传的 Android 图库与图片阅读应用。项目以真实文件系统目录为相册最小管理单元，整合本地结构化并发索引、双车道路径互斥调度、无公网依赖的局域网发现共享与增量下载，以及面向长图与多格式动图优化的大图阅读器。

## 核心功能

### 本地图库管理
- **真实目录导入与增量同步**：通过 SAF 选择本地根目录并递归扫描所有含图片的子目录，按目录路径在 Room 事务内执行差分更新，同步过程中保留相册分组、收藏状态、自定义排序与阅读进度。
- **分组、收藏与检索**：支持新建、重命名、删除分组，支持收藏筛选、标题关键词检索以及按自定义拖拽顺序、名称、修改时间、图片数量与容量排序。
- **多选与拖拽排序**：支持相册长按多选、批量移动分组、批量删除与拖拽调整顺序。
- **缩略图与隐藏目录管理**：支持 `.thumb` 封面缩略图预生成与 `.nomedia` 隐藏切换。

### 局域网无感共享与跨设备下载
- **mDNS / DNS-SD 自动发现**：同网段设备自动广播与发现，内置自过滤、设备标识去重与轻量 TCP 探活，自动过滤系统 `mdnsd` 缓存残留的失效设备。
- **配对令牌鉴权与实时事件推送**：服务端内嵌 Ktor CIO HTTP 引擎，采用一次性配对审批与请求头令牌鉴权；通过 SSE 长连接实时推送主机相册变更、配对审批结果与离线状态。
- **跨设备增量对比下载与断点续传**：下载前比对远端清单与本地文件大小跳过未变更图片，流式写入 `.part` 临时文件并原子重命名；搭配 Android 前台服务实时展示进度与速率，支持暂停续传、取消并清除已下文件，以及本地删除与在途下载的联动取消。

### 大图阅读器（PhotoViewer）
- **多阅读模式**：支持横向从左至右 / 从右至左分页阅读与连续纵向长图滚动阅读。
- **手势与交互**：支持双击多档循环缩放、双指捏合缩放与惯性平移、四向边缘阻尼反馈、音量键连续翻页、自动定时翻页与底部双向进度条。
- **动图与沉浸式适配**：支持 GIF 及动态 WebP 播放与生命周期托管；支持全屏沉浸式阅读、刘海安全区避让、自定义亮度、屏幕方向锁定与顶部悬浮信息栏（电量、页码、时间）。

## 架构设计

### 模块结构

```text
app/src/main/java/com/giant8east/photomanager/
├── data/
│   ├── album/          # 相册查询、同步入口与单目录索引器（AlbumIndexer / AlbumSyncData）
│   ├── database/       # Room 数据库、实体与 DAO
│   ├── delete/         # 应用级批量删除协调器与单层文件删除器
│   ├── group/          # 相册分组管理
│   ├── lan/            # 远端相册缓存、下载路径配置与串行下载调度器（RemoteDownloadManager）
│   ├── media/          # 图片格式识别、动图头检测与 .thumb 生成
│   ├── scan/           # 目录遍历扫描、扫描根配置与应用级同步管理器（SyncManager）
│   ├── settings/       # DataStore 列表与阅读配置持久化
│   └── task/           # 双车道路径互斥调度器（FolderTaskScheduler）
├── di/                 # Hilt 依赖注入模块
├── lan/
│   ├── client/         # mDNS 浏览、HTTP 客户端、SSE 事件流、配对流程与下载前台服务
│   ├── protocol/       # 局域网协议 DTO 与常量定义
│   └── server/         # Ktor CIO 嵌入式服务、路由鉴权、mDNS 广播与主机管理
├── navigation/         # 保活导航栈与水平转场动画
├── pages/
│   ├── album/          # 本地相册库页、卡片、拖拽状态与弹窗
│   ├── albumdetail/    # 本地相册详情页与三列网格画廊
│   ├── common/         # 通用弹窗、图片组件（AppMediaImage）与复用卡片骨架
│   ├── groupmanager/   # 分组管理页
│   ├── lan/            # 远端相册列表、远端详情与下载状态展示
│   ├── photoviewer/    # 大图阅读器（布局管理、手势、边缘效果、控制栏与系统窗口控制）
│   └── setting/        # 设置页、本地目录导入页与局域网共享连接页
└── ui/                 # 主题配色（PhotoManagerTheme）、通用状态与辅助组件
```

### 关键技术机制

- **双车道路径互斥调度器（`FolderTaskScheduler`）**：将文件删除串行车道（1 Worker）与数据扫描并发车道（4 Workers）隔离，配合路径级占用锁（`ActiveClaim`）、同类型任务去重及四级优先级（`UNINDEX > DELETE_FILES > SCAN_IMMEDIATE > SCAN_REFRESH`），确保全盘同步、单目录刷新、远端下载入库与本地删除严格互斥且互不阻塞；删除采用「先删磁盘、再交接高优即时扫描核验」策略，以磁盘真实状态作为唯一成功判据。
- **渲染与转场优化**：自定义 Coil `Interceptor` 将 `.thumb` 磁盘检测下沉至后台线程；页面转场期间延迟非首屏网格解码，复用列表封面内存缓存键作为详情页占位并结合 GPU 图层透明度渐显消除闪烁；连续纵向阅读通过单页实测尺寸回报与 `dispatchRawDelta` 同步偏移补偿，避免异步加载高度变化导致的向上回弹。

## 技术栈

| 类别 | 技术选型 |
| --- | --- |
| 开发语言 | Kotlin 2.0.21 |
| 异步与并发 | Kotlin Coroutines 1.8.1、Kotlin Flow |
| UI 框架 | Jetpack Compose (BOM 2024.09.00)、Material 3 |
| 持久化与分页 | Room 2.6.1、Paging 3.3.2、DataStore 1.1.1 |
| 依赖注入 | Hilt 2.51.1 |
| 图片加载 | Coil 2.6.0（含 `coil-gif`） |
| 局域网通信 | Android NSD (mDNS / DNS-SD)、Ktor Server CIO 2.3.13、OkHttp 4.12.0、SSE、Kotlinx Serialization |
| 列表交互 | `sh.calvin.reorderable` 2.4.3、`LazyColumnScrollbar` 2.2.0 |

## 环境要求与构建

- **Android 版本**：最低支持 Android 11（`minSdk = 30`），编译与目标版本为 Android 14（`compileSdk = 34`、`targetSdk = 34`）。
- **构建工具**：Android Studio（AGP 8.6.1）、JDK 17+。

### 编译与运行

```bash
git clone https://github.com/Giant8east/PhotoManager.git
cd PhotoManager
./gradlew :app:assembleDebug
```

或直接使用 Android Studio 打开项目并运行 `app` 模块。

### 权限说明

- **所有文件访问权限（`MANAGE_EXTERNAL_STORAGE`）**：应用直接基于真实目录路径扫描、排序和删除相册，首次启动时将引导前往系统设置授予该权限。
- **局域网与通知权限**：开启局域网共享或跨设备下载时使用 Wi-Fi 组播（`CHANGE_WIFI_MULTICAST_STATE`）及前台服务通知权限（Android 13+ 需授予 `POST_NOTIFICATIONS`）。

## 致谢

应用在图库卡片、详情画廊、阅读交互及明暗配色等体验细节上参考了开源项目 [EhViewer](https://github.com/seven332/EhViewer) 的设计思路，在此表示感谢。
