package app.sanefeed.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import app.sanefeed.SaneFeedApp
import app.sanefeed.core.Platform

/**
 * Debug builds only: drives the phone through the service so on-device checks work where shell
 * input injection is denied (HyperOS). Only senders holding DUMP, i.e. `adb shell`, can trigger it:
 *
 * `adb shell am broadcast -a app.sanefeed.debug.SWIPE --ei fromY 2000 --ei toY 800 --el ms 300`
 * `adb shell am broadcast -a app.sanefeed.debug.TAP --ei x 600 --ei y 1200`
 * `adb shell am broadcast -a app.sanefeed.debug.BACK`
 * `adb shell am broadcast -a app.sanefeed.debug.TEXT --es text hamidofx1` (into the focused field)
 * `adb shell am broadcast -a app.sanefeed.debug.TREE` (logs the active window under tag SaneFeedTree,
 *   including the fields `uiautomator dump` leaves out)
 * `adb shell am broadcast -a app.sanefeed.debug.COUNT --es platform x --ei count 0` (today's count)
 * `adb shell am broadcast -a app.sanefeed.debug.LIMIT --es platform x --ei limit 100` (daily limit)
 * `adb shell am broadcast -a app.sanefeed.debug.BLOCK --es platform x --es handle someone` (manual list; UNBLOCK removes)
 *
 * SWIPE also takes `x` (default: screen centre); TAP takes `ms` (default 50).
 */
internal object TestHooks {
    private const val SWIPE = "app.sanefeed.debug.SWIPE"
    private const val TAP = "app.sanefeed.debug.TAP"
    private const val BACK = "app.sanefeed.debug.BACK"
    private const val TEXT = "app.sanefeed.debug.TEXT"
    private const val TREE = "app.sanefeed.debug.TREE"
    private const val TREE_TAG = "SaneFeedTree"
    private const val COUNT = "app.sanefeed.debug.COUNT"
    private const val LIMIT = "app.sanefeed.debug.LIMIT"
    private const val BLOCK = "app.sanefeed.debug.BLOCK"
    private const val UNBLOCK = "app.sanefeed.debug.UNBLOCK"

    private var receiver: BroadcastReceiver? = null

    fun attach(service: AccessibilityService) {
        if (receiver != null) return
        val hook = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val centreX = service.resources.displayMetrics.widthPixels / 2
                when (intent.action) {
                    SWIPE -> stroke(
                        service,
                        intent.getIntExtra("x", centreX).toFloat(),
                        intent.getIntExtra("fromY", 0).toFloat(),
                        intent.getIntExtra("toY", 0).toFloat(),
                        intent.getLongExtra("ms", 300),
                    )
                    TAP -> {
                        val y = intent.getIntExtra("y", 0).toFloat()
                        stroke(service, intent.getIntExtra("x", centreX).toFloat(), y, y, intent.getLongExtra("ms", 50))
                    }
                    BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    TEXT -> service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, intent.getStringExtra("text"))
                        },
                    )
                    TREE -> service.rootInActiveWindow?.let { log(it, 0) }
                    COUNT, LIMIT, BLOCK, UNBLOCK -> {
                        val graph = (service.application as SaneFeedApp).graph
                        val platform = Platform.fromId(intent.getStringExtra("platform")) ?: return
                        val handle = intent.getStringExtra("handle").orEmpty()
                        when (intent.action) {
                            COUNT -> graph.counter.setCount(platform, intent.getIntExtra("count", 0))
                            LIMIT -> graph.settings.setLimit(platform, intent.getIntExtra("limit", 0))
                            BLOCK -> graph.settings.addManual(platform, handle)
                            else -> graph.settings.removeManual(platform, handle)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(SWIPE)
            addAction(TAP)
            addAction(BACK)
            addAction(TEXT)
            addAction(TREE)
            addAction(COUNT)
            addAction(LIMIT)
            addAction(BLOCK)
            addAction(UNBLOCK)
        }
        ContextCompat.registerReceiver(service, hook, filter, android.Manifest.permission.DUMP, null, ContextCompat.RECEIVER_EXPORTED)
        receiver = hook
    }

    fun detach(service: AccessibilityService) {
        receiver?.let(service::unregisterReceiver)
        receiver = null
    }

    private fun stroke(service: AccessibilityService, x: Float, fromY: Float, toY: Float, ms: Long) {
        val path = Path().apply {
            moveTo(x, fromY)
            lineTo(x, toY)
        }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        service.dispatchGesture(gesture, null, null)
    }

    private fun log(node: AccessibilityNodeInfo, depth: Int) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val fields = listOfNotNull(
            node.className?.toString()?.substringAfterLast('.'),
            node.viewIdResourceName?.let { "id=$it" },
            node.text?.let { "text=$it" },
            node.contentDescription?.let { "desc=$it" },
            node.hintText?.let { "hint=$it" },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) node.stateDescription?.let { "state=$it" } else null,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.tooltipText?.let { "tooltip=$it" } else null,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.paneTitle?.let { "pane=$it" } else null,
            node.extras.keySet().takeIf { it.isNotEmpty() }?.let { "extras=$it" },
            if (node.isScrollable) "scrollable" else null,
            if (node.isClickable) "clickable" else null,
            if (node.isSelected) "selected" else null,
            if (!node.isVisibleToUser) "invisible" else null,
            bounds.toShortString(),
        )
        Log.d(TREE_TAG, "  ".repeat(depth) + fields.joinToString(" ").replace('\n', ' '))
        for (i in 0 until node.childCount) node.getChild(i)?.let { log(it, depth + 1) }
    }
}
