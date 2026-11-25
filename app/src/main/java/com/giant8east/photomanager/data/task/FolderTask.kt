/** 文件夹任务模型：调度的最小单位是一个文件夹，任务类型决定车道、优先级与占用范围。 */
package com.giant8east.photomanager.data.task

/** 执行车道：文件操作与数据库操作互不阻塞，各自排自己的队。 */
enum class TaskLane {
    /** 文件车道：只碰磁盘；1 个 worker，让删除串行、进度可解释。 */
    FILES,

    /** 数据车道：读写数据库；可以并发。 */
    DATA
}

/** 任务占用的路径范围；两个任务的占用范围有交集时不能同时运行。 */
enum class ClaimScope {
    /** 只占用这个文件夹本身。 */
    SELF,

    /** 占用这个文件夹及其全部子目录。 */
    SUBTREE
}

/**
 * 任务类型。
 *
 * 优先级是任务自己的属性而不是队列的属性：同一个文件夹上同时排出删除与扫描时，
 * 删除先拿到路径，避免扫描先写回一批马上要被删掉的记录。
 */
enum class TaskKind(val lane: TaskLane, val priority: Int, val claimScope: ClaimScope) {
    /** 只删除相册记录（含子目录），不碰文件；调用方已经移除扫描目录配置。 */
    UNINDEX(TaskLane.DATA, 30, ClaimScope.SUBTREE),

    /** 只删除这个文件夹本层的图片与点文件，不进入子目录，也不写数据库。 */
    DELETE_FILES(TaskLane.FILES, 20, ClaimScope.SELF),

    /** 删除后核验、手动刷新与下载完即时入库；优先级高于全盘同步，避免排在几百个目录队尾。 */
    SCAN_IMMEDIATE(TaskLane.DATA, 18, ClaimScope.SELF),

    /** 后台全盘同步的单目录扫描任务。 */
    SCAN_REFRESH(TaskLane.DATA, 15, ClaimScope.SELF)
}

/** 一个待执行的任务；path 必须是规范化绝对路径。 */
data class FolderTask(
    val path: String,
    val kind: TaskKind
)

/** 一次任务执行的结果。 */
sealed interface TaskResult {
    /** 本次删掉的文件数；只有删除类任务会带值，其余类型恒为 0。 */
    val deletedFileCount: Long
        get() = when (this) {
            is Done -> deletedFiles
            is Failed -> deletedFiles
            Skipped, Cancelled -> 0
        }

    /** 本次没删掉的文件数；含义同上。 */
    val failedFileCount: Int
        get() = when (this) {
            is Done -> failedFiles
            is Failed -> failedFiles
            Skipped, Cancelled -> 0
        }

    /** 正常结束；删除任务带上本次删掉与失败的图片数。 */
    data class Done(val deletedFiles: Long = 0, val failedFiles: Int = 0) : TaskResult

    /** 执行失败；[message] 用于界面提示，可以为空。 */
    data class Failed(
        val message: String?,
        val deletedFiles: Long = 0,
        val failedFiles: Int = 0
    ) : TaskResult

    /** 按设计不需要处理（例如路径已被更高级任务接管）。 */
    data object Skipped : TaskResult

    /** 排队期间被更高优先级的任务替换，没有执行。 */
    data object Cancelled : TaskResult
}
