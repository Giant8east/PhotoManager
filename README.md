# PhotoManager

PhotoManager 是一款基于 Kotlin + Jetpack Compose 的 Android 本地图片管理应用，直接读取系统 MediaStore，并使用 Room 保存收藏、标签和应用内整理信息。

当前阶段：需求分析和方案设计。

- [需求分析](需求分析.md)
- [方案设计](方案设计.md)

## 技术方向

- Kotlin + Jetpack Compose
- MVVM：UI -> ViewModel -> Data
- MediaStore 图片访问
- Room 本地持久化
- Coil 缩略图加载
- Paging 3 分页
- WorkManager 后台同步
