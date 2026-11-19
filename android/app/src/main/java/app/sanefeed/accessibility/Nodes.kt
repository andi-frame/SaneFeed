package app.sanefeed.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import java.util.IdentityHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Node queries that never throw: the target app can change its tree mid-scan, which surfaces as
 * runtime exceptions from the accessibility IPC layer. Lookups use the app-side id/text search
 * (one IPC round trip each) where they work; Compose apps are walked instead.
 */
internal object Nodes {
    /** The bottom navigation bar sits in the lowest 1/[BOTTOM_NAV_FRACTION] of the window. */
    const val BOTTOM_NAV_FRACTION = 6

    /** Visible nodes for the first id in [ids] that has any. */
    fun byId(root: AccessibilityNodeInfo, ids: List<String>): List<AccessibilityNodeInfo> {
        for (id in ids) {
            val found = visible { root.findAccessibilityNodeInfosByViewId(id) }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /** Visible nodes for every id in [ids]. */
    fun allById(root: AccessibilityNodeInfo, ids: List<String>): List<AccessibilityNodeInfo> =
        ids.flatMap { id -> visible { root.findAccessibilityNodeInfosByViewId(id) } }

    /**
     * Nodes for the first id in [ids] that has any, including ones laid out just off screen: a
     * list row that is partly visible still exposes its hidden children this way.
     */
    fun laidOutById(root: AccessibilityNodeInfo, ids: List<String>): List<AccessibilityNodeInfo> {
        for (id in ids) {
            val found = safe { root.findAccessibilityNodeInfosByViewId(id) }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    fun firstById(root: AccessibilityNodeInfo, ids: List<String>): AccessibilityNodeInfo? = byId(root, ids).firstOrNull()

    /** Visible nodes whose text or content description contains [text], ignoring case. */
    fun byText(root: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> =
        visible { root.findAccessibilityNodeInfosByText(text) }

    fun children(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        (0 until childCount(node)).mapNotNull { child(node, it) }

    fun parent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? = try {
        node.parent
    } catch (_: RuntimeException) {
        null
    }

    fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    /** Content description, falling back to text. */
    fun label(node: AccessibilityNodeInfo): CharSequence? = node.contentDescription ?: node.text

    /** A node in the bottom navigation bar labelled exactly one of [labels], ignoring case. */
    fun bottomTab(root: AccessibilityNodeInfo, screen: Rect, labels: List<String>): AccessibilityNodeInfo? {
        val navTop = screen.bottom - screen.height() / BOTTOM_NAV_FRACTION
        for (label in labels) {
            for (node in byText(root, label)) {
                if (label.contentEquals(label(node), ignoreCase = true) && bounds(node).top >= navTop) return node
            }
        }
        return null
    }

    /** Whether [node] fills most of [screen], i.e. it is a full-screen viewer rather than a preview. */
    fun coversScreen(node: AccessibilityNodeInfo, screen: Rect): Boolean =
        bounds(node).height() * 10 >= screen.height() * 6

    fun childCount(node: AccessibilityNodeInfo): Int = try {
        node.childCount
    } catch (_: RuntimeException) {
        0
    }

    /**
     * Children of each of [parents], all requested at once. While X scrolls it answers one request
     * per gap between frames and invalidates the node cache many times a second, so a walk that
     * asks node by node waits a frame per node; concurrent requests share the wait. They skip
     * prefetching, whose results would be invalidated before use and only cost the app time.
     */
    fun childrenOfAll(parents: List<AccessibilityNodeInfo>): List<List<AccessibilityNodeInfo>> {
        val jobs = parents.map { parent -> List(childCount(parent)) { i -> pool.submit<AccessibilityNodeInfo?> { child(parent, i, prefetch = false) } } }
        return jobs.map { children -> children.mapNotNull(::await) }
    }

    /** Refreshes [nodes] at once (see [childrenOfAll]); false for nodes that are gone. */
    fun refreshAll(nodes: List<AccessibilityNodeInfo>): List<Boolean> =
        nodes.map { node -> pool.submit<Boolean> { refresh(node) } }.map { await(it) == true }

    /**
     * The subtrees below [roots], fetched a level at a time with [childrenOfAll]: about one wait
     * per level instead of one per node. [descend] says whether to fetch a node's children; depth
     * 1 is a root's child. Stops after about [maxNodes] nodes. Maps each fetched parent, by
     * identity, to its children.
     */
    fun subtrees(
        roots: List<AccessibilityNodeInfo>,
        maxNodes: Int,
        descend: (AccessibilityNodeInfo, Int) -> Boolean,
    ): Map<AccessibilityNodeInfo, List<AccessibilityNodeInfo>> {
        val fetched = IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>()
        var level = roots
        var depth = 1
        var count = 0
        while (level.isNotEmpty() && count < maxNodes) {
            val next = ArrayList<AccessibilityNodeInfo>()
            childrenOfAll(level).forEachIndexed { i, children ->
                fetched[level[i]] = children
                count += children.size
                children.filterTo(next) { descend(it, depth) }
            }
            level = next
            depth++
        }
        return fetched
    }

    /** Child and refresh requests made so far; debug scan logs read the difference. */
    val requests = AtomicInteger()

    private fun child(node: AccessibilityNodeInfo, index: Int, prefetch: Boolean = true): AccessibilityNodeInfo? = try {
        requests.incrementAndGet()
        if (!prefetch && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) node.getChild(index, 0) else node.getChild(index)
    } catch (_: RuntimeException) {
        null
    }

    private fun refresh(node: AccessibilityNodeInfo): Boolean = try {
        requests.incrementAndGet()
        node.refresh()
    } catch (_: RuntimeException) {
        false
    }

    private fun <T> await(job: Future<T>): T? = try {
        job.get()
    } catch (_: ExecutionException) {
        null
    }

    /** Threads idle out a few seconds after the last scan. */
    private val pool: ExecutorService by lazy {
        val threads = AtomicInteger()
        ThreadPoolExecutor(PARALLEL_REQUESTS, PARALLEL_REQUESTS, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
            Thread(task, "sanefeed-fetch-${threads.incrementAndGet()}")
        }.apply { allowCoreThreadTimeOut(true) }
    }

    /** Enough for every row of a screen at once without flooding the app's UI thread. */
    private const val PARALLEL_REQUESTS = 12

    private inline fun visible(query: () -> List<AccessibilityNodeInfo>?): List<AccessibilityNodeInfo> =
        safe(query).filter { it.isVisibleToUser }

    private inline fun safe(query: () -> List<AccessibilityNodeInfo>?): List<AccessibilityNodeInfo> = try {
        query().orEmpty()
    } catch (_: RuntimeException) {
        emptyList()
    }
}
