package cc.pulsez.app.ui

/**
 * 分享文本的「一次性消费」判定与分享入口路由判定（v0.5.5 v0.5.6）。
 *
 * ## 为什么需要（v0.5.5）
 * `MainActivity` 把启动分享文本交给 `AppRoot` 后**不清空**
 * 自己持有的那份文本；而 `AndroidManifest.xml` 的 `configChanges` 不含 fontScale / locale /
 * density，这些配置变化会**重建 Activity**、`onCreate` 会从同一个 Intent 再取出同一份文本；
 * 同时清空换代（`AppContainer.wipeEpoch` 自增，v0.5.6 起为所有 Activity 实例共享）会换掉导入页的
 * `ImportViewModel`，新 VM 没有「这份分享已消费」的记忆。于是同一份分享文本会被重新交给新 VM
 * → 重新解析出清空前的备份预览 → 仍可一键恢复。
 *
 * 修法：把「已消费」记号提到 `AppRoot` 组合层（`rememberSaveable`），并按**文本内容**去重——
 * 只有非空、且不等于上一次已消费的文本才注入。VM 内部的「已识别过的分享文本」
 * （`ImportViewModel.onShareText` 的文本比较，v0.5.6 起）保留为第二道防线。
 *
 * ## v0.5.6 温启动也走事件流
 * v0.5.5 之前，`MainActivity.onNewIntent` 只改普通字段，Compose 树不重建 → 同一 Activity 内
 * 再分享的**不同**文本到不了导入页（` 已登记该缺口）。v0.5.6 改成
 * Activity 级 `StateFlow` 事件流：`MainActivity.onNewIntent` 写入 → `AppRoot` 观察到变化 →
 * ① 当前不在导入页就 `nav.push(Route.Import)` 合成返回栈；② 经本文件的 [shouldInject] 按文本
 * 去重后注入导入 VM。所以**温启动的不同文本照常生效**（不再是文档/注释里那句与实现不符的话），
 * 冷启动的「首页为根、导入页压栈」语义不变。
 *
 * ## 处理原则
 * **同一份文本第二次分享会被忽略。** 用户清空后重新分享完全相同的备份文本时不会解析；
 * 重新选文件 / 粘贴同一份内容仍可用（那是文件/粘贴入口，不经过这里）。
 * 「同一 Activity 内再次分享**不同**文本」经事件流照常注入（[shouldInject] 判定为未消费）。
 * 另外 `StateFlow` 相等值不发事件：同一份文本再次分享连导航都不会触发（值未变），
 * 这与「同一份文本只消费一次」一致，不额外压栈。
 *
 * ## 为什么是纯函数
 * `AppRoot` 的 `LaunchedEffect` 跑不到纯 JVM 单测里（本仓库没有 Compose UI 测试基建，
 * 见 `），把判定抽出来后才能用 `ShareConsumptionTest` 直接锁判定表；
 * 接线本身由 `AppRootWipeWiringSourceScanTest` / `ShareEventWiringSourceScanTest` 的源码扫描锁。
 */
internal object ShareConsumption {

    /**
     * [text] 是否应该交给导入页的 `ImportViewModel`。
     *
     * 只有「非空（[String.isBlank] 也算空）、且不等于 [lastConsumed]」才注入。
     * `null` / 空白的 [text] 永不注入；[lastConsumed] 为 `null` 表示本次进程还没消费过文本。
     *
     * 判定**不 trim、不改写文本**：注入的仍是原文（避免改变识别输入；`MainActivity` 已经在
     * 入口用 `takeIf { it.isNotBlank() }` 过滤过空白文本，这里只做防御）。
     */
    fun shouldInject(text: String?, lastConsumed: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text != lastConsumed
    }

    /**
     * 收到分享文本时是否要**压入**导入页（v0.5.6 温启动事件流）。
     *
     * 只有「非空文本」且「当前不在导入页」才需要压栈：已经在导入页时只注入、不重复压栈
     * （否则一次分享会叠两层导入页，返回手势要按两次才回首页）。
     * 冷启动时导航栈只有首页，这里返回 true → `nav.push(Route.Import)`，保持
     * 「首页为根、导入页在其上」的既有返回栈语义。
     *
     * 注意这一判定**与文本去重无关**：同一份文本再次到达（例如 Activity 重建后事件流回放）
     * 也会压栈——重建后导航栈本来就是新建的，需要它才能回到导入页；是否注入仍由 [shouldInject]
     * 单独决定（同一份文本不会重复注入）。
     */
    fun shouldOpenImportPage(text: String?, alreadyOnImport: Boolean): Boolean {
        if (text.isNullOrBlank()) return false
        return !alreadyOnImport
    }
}
