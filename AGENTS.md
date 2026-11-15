# PhotoManager 项目开发规范

## 参考实现

- 本 App 以 EhViewer（`E:\software\AndroidProject\Ehviewer_CN_SXJ`）为主要学习和交互参考对象。
- 遇到功能实现、架构取舍或交互问题时，优先阅读并对比 EhViewer 对应源码，判断其方案是否更加简洁、合理、成熟，再结合当前 Compose 与 MVVM 架构实施。
- 对比结论应基于具体代码，区分本地目录与网络图库等不同实现，不凭界面表现推断内部机制；不机械照搬其旧框架或增加无必要抽象。
- 当前文件操作以整个相册目录为最小单位，不为 App 内逐张删除图片设计复杂的位置恢复；阅读索引变化后立即请求写入 Room 的相册最后查看位置。

## Kotlin 语法糖注释（学习型代码）

- 项目所有者正在系统学习 Kotlin 与网络编程，代码要能被逐行读懂；**允许使用语法糖，但必须让读者看懂它等价于什么**。
- 使用下列语法糖时，在行尾或上一行加一句简短注释，写出直白等价写法；注释只解释语法，不复述业务：
  - `?.let { }` / `?.let(::f)` → `if (x != null) f(x)`
  - `::method` 方法引用 → `{ arg -> method(arg) }`
  - `also` / `apply` / `run` / `with` → 展开成多行
  - `T.() -> Unit` 带接收者 lambda → `(T) -> Unit`，说明 lambda 内 `this` 是谁
  - `fun Receiver.name(...)` 扩展函数 → `fun name(receiver: Receiver, ...)`
  - `?: return@label` → `if (x == null) return`（说明标签返回到哪一层）
  - `companion object` / `const val` / `private set` / `data class` / `by lazy` / `object`
  - `firstOrNull()`、`?.`、`?:` 组合 → 展开成 `if` 嵌套
- 注释示例：

```kotlin
// 等价于 if (previous != null) tokenToDevice.remove(previous)
deviceToToken.put(deviceId, token)?.let(tokenToDevice::remove)

// 等价于 if (header != null) store.resolve(header) else null
request.header(TOKEN_HEADER)?.let(tokens::resolve)

// 等价于 fun lanModule(app: Application, ...)，调用时 app 作为接收者（this）
fun Application.lanModule(...)
```

- 语法糖只用于确实更简洁的场景；若直白写法同样清楚，优先直白写法（例如 `val bytes = ByteArray(n); random.nextBytes(bytes)` 优于 `ByteArray(n).also(random::nextBytes)`）。

## 防御与错误处理规范

- **只在边界防御**：外部输入（HTTP 参数/头/路径、文件系统与 SAF Uri、系统回调、第三方响应、数据库读出的可空字段）必须校验；自己类之间的调用靠类型与测试保证，不加运行时判断。
- **失败必须显式**：不返回假值、不吞异常。兜底值只在业务上等价时使用（例如默认排序），不能用它掩盖资源未就绪（反例：端口解析失败却返回 0 并广播出去）。
- **每个 `catch` / `runCatching` 都要有归宿**：必须能回答"失败后用户看到什么、状态如何恢复、日志在哪里"；答不上来就不要捕获，让异常向上传播。
- **横切逻辑出现第二次就抽**：鉴权、参数校验、错误映射、超时、日志等，抽成插件/拦截器/共享函数。例：服务端受保护路由统一写在 `authenticated(tokens) { ... }` 块里，不在每个 handler 重复 `requireDevice`。
- **用类型表达状态**：`sealed interface` 优于多个布尔；`null` 只表示"不存在"，不要兼任"未初始化/未知/失败"。例：`checkNotNull(call.deviceId())` 表示"拦截器已保证存在"，违反即代码 bug，应当立刻暴露，而不是静默返回空响应。
- **防御性代码必须写注释**：注明"防的是什么场景、触发条件、期望行为"；没有场景的额外判断视为过度防御，评审时直接删。
- **不重复校验**：边界校验一次，下游信任契约；各层重复校验会产生无法测试的分支。
- **过度防御也是成本**：每个多余判断都会掩盖真实 bug、制造测不到的分支、增加阅读负担，并诱导更多层防御。

### 评审时直接问的 6 个问题

1. 这个 `if` 在什么真实场景下会为真？有测试能触发吗？
2. 如果删掉它，会发生什么？测试会红吗？
3. 这个错误被吞了吗？用户和日志能看到吗？
4. 这段逻辑是第几次出现？该抽了吗？
5. 这个 `null` 是在表达"不存在"，还是在掩饰"还没准备好"？
6. 这个兜底值如果错了，会不会让问题更晚暴露？

## 数据层文件注释

- `data` 目录下的每个源代码文件都必须添加文件级中文注释。
- 文件级注释放在 `package` 声明之前，用简短内容说明该文件的职责、数据边界或与其他层的关系。
- 注释说明设计意图和职责，不重复类名或逐行解释实现。
- 新增 `database`、`media`、`dao`、`mapper`、`data source` 或其他数据层文件时，必须同步添加文件级注释。
- `di` 目录中的依赖注入模块也必须添加文件级中文注释，说明它提供或组装的依赖。

示例：

```kotlin
/** 相册数据库实体，保存目录相册的展示信息、路径和所属分组。 */
package com.giant8east.photomanager.data.database
```

## ViewModel 规范

- 所有页面 ViewModel 直接继承 AndroidX ViewModel；当前不增加仅用于统一写法的基类。
- Compose 页面可变状态统一使用 `var ... by mutableStateOf(...)`（或基本类型专用状态）配合 `private set`，不重复维护 `_属性` 与仅转发的 getter；派生属性使用只读 `val` getter。
- 对外不暴露 MutableStateFlow、MutableList 等可变实现。内部使用 MutableStateFlow 时通过 asStateFlow 暴露；已有 Flow、StateFlow 和 Flow<PagingData<...>> 本身为只读接口，不为形式统一重复包装或转换。
- 数据观察及业务异步任务由 ViewModel 在 viewModelScope 内管理；对外提供普通动作方法，例如 load、saveSettings。切换查询参数时取消旧任务，避免重复收集；协程取消必须继续抛出。
- 页面只触发加载及用户动作；Pager、手势、窗口绑定等界面生命周期行为留在 UI 或页面专用系统控制器中，不将 Activity、PagerState 放进 ViewModel。
- 数据驱动页面统一通过 PageState 的 Loading、Content、Message 表达主数据状态；Content 一次交付页面所需数据。正常空结果不提供重试，读取失败保留原因并支持重试；重试先取消旧观察，再进入 Loading，协程取消继续抛出。
- 操作过程优先复用已有页面状态，避免为每个动作新增布尔字段。相册详情刷新和删除由 ViewModel 在 viewModelScope 中执行并处理异常，允许注入应用级 Context 显示原生 Toast；不得持有 Activity、页面 Context 或导航器。页面只调用动作，并在完成回调中决定返回，不重复启动操作协程或处理业务异常。正常刷新保留内容和 Room 观察，读取失败后的刷新重新启动观察；删除进入 Loading，失败恢复内容和观察。后台同步仍独立维护状态，菜单和弹窗留在 UI。单张图片加载与解码状态由单页组件管理，不升级为阅读页主状态。
- UiText 只承载资源 ID 或运行时文本，字符串资源由 Compose 解析。不引入 ViewModel 基类，不因统一主状态修改数据库或同步算法。导航条目独立持有 ViewModelStore，被覆盖时保留，出栈转场结束后清理。

## 数据层边界

- Data 层统一处理外部数据读取、默认值、空值归一化、格式转换和异常数据。
- ViewModel 和 Compose UI 不重复处理同一套数据清洗逻辑。
- 当前阶段允许 Room Entity 直接作为 Data、ViewModel 和 UI 使用的模型；只有数据库结构与业务含义明显不一致时才拆分 Model 和 Mapper。

## DAO 命名与写入规范

- DAO 方法名必须对应实际数据库行为：读取包含 `query` / `Query`，新增使用 `insert`，修改使用 `update`，删除使用 `delete`，并说明操作对象。
- Flow 查询使用 `observe…Query`，例如 `observeAlbumQuery`、`observeGroupQuery`；单次读取例如 `queryAlbumByPath`。
- 命名依据实际 SQL 行为，不能将使用 `@Query` 执行 DELETE 的方法命名为查询。
- Data 层可保留业务名称，例如 `observeAlbums`、`importDirectory`。
- 新记录不显式指定自增 ID，由数据库生成；更新使用查询到的已有实体 ID，不修改主键。
- 重复导入目录时，在同一数据库事务中按唯一目录路径查询：不存在则插入，存在则更新，保留 ID、创建时间、分组和手动排序。不使用 REPLACE 替代更新，避免级联删除关联记录。
