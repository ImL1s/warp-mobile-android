package dev.warp.mobile

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

data class VirtualBlockNode(
    val id: Int,
    val text: String,
    val output: String = "",
    val bounds: Rect = Rect(0, 0, 0, 0)
)

class WarpAccessibilityNodeProvider(
    private val hostView: View,
    private val virtualBlocks: List<VirtualBlockNode> = emptyList(),
    private val onAction: ((virtualId: Int, action: Int) -> Unit)? = null,
    private val onCopyOutput: ((virtualId: Int) -> Unit)? = null
) : AccessibilityNodeInfoProviderBridge() {

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        if (virtualViewId == View.NO_ID || virtualViewId == HOST_VIEW_ID) {
            val hostInfo = AccessibilityNodeInfo.obtain(hostView)
            hostInfo.className = hostView.javaClass.name
            hostInfo.setPackageName(hostView.context.packageName)
            hostInfo.setSource(hostView)
            hostInfo.isFocusable = true
            hostInfo.isFocused = hostView.isFocused
            hostInfo.isVisibleToUser = true
            for (block in virtualBlocks) {
                hostInfo.addChild(hostView, block.id)
            }
            return hostInfo
        }

        val block = virtualBlocks.find { it.id == virtualViewId } ?: return null
        val childInfo = AccessibilityNodeInfo.obtain(hostView, virtualViewId)
        childInfo.className = "dev.warp.mobile.VirtualBlockNode"
        childInfo.setPackageName(hostView.context.packageName)
        childInfo.setSource(hostView, virtualViewId)
        childInfo.setParent(hostView)
        childInfo.text = block.text
        childInfo.contentDescription = if (block.output.isNotBlank()) "${block.text}\n${block.output}" else block.text
        childInfo.setBoundsInParent(block.bounds)
        childInfo.isFocusable = true
        childInfo.isFocused = false
        childInfo.isVisibleToUser = true
        childInfo.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS)
        childInfo.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_accessibility_copy_output, "Copy output"))
        return childInfo
    }

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        onAction?.invoke(virtualViewId, action)
        if (action == R.id.action_accessibility_copy_output) {
            onCopyOutput?.invoke(virtualViewId)
            return true
        }
        if (action == AccessibilityNodeInfo.ACTION_FOCUS || action == AccessibilityNodeInfo.ACTION_CLEAR_FOCUS) {
            return true
        }
        return false
    }

    companion object {
        const val HOST_VIEW_ID = -1
    }
}

abstract class AccessibilityNodeInfoProviderBridge : android.view.accessibility.AccessibilityNodeProvider()
