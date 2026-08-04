package dev.warp.mobile

import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.input.TextFieldValue
import app.cash.turbine.test
import dev.warp.mobile.test.BaseWarpUnitTest
import dev.warp.mobile.ui.DeXLayoutHandler
import dev.warp.mobile.ui.DevicePosture
import dev.warp.mobile.ui.FoldOrientation
import dev.warp.mobile.ui.FoldablePostureHandler
import dev.warp.mobile.ui.PromptComposer
import dev.warp.mobile.ui.WarpAdaptiveLayout
import dev.warp.mobile.ui.WarpLayoutType
import dev.warp.mobile.ui.WindowSizeClass
import dev.warp.mobile.ui.WindowSizeClassHandler
import dev.warp.mobile.ui.WindowSizeClassResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class Task5AdaptiveAccessibilityTest : BaseWarpUnitTest() {

    private lateinit var activity: ComponentActivity

    @Before
    override fun setUp() {
        super.setUp()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    }

    // -------------------------------------------------------------------------
    // SUITE 1: WindowSizeClass & Layout Profile Calculations
    // -------------------------------------------------------------------------

    @Test
    fun testWindowSizeClass_compactWidth_resolvesCompactSinglePane() {
        val windowState = WindowSizeClassResolver.resolve(widthDp = 360, heightDp = 800)
        assertEquals(WindowSizeClass.Compact, windowState.widthSizeClass)
        assertFalse("Compact layout should disable dual pane", windowState.isDualPane)
    }

    @Test
    fun testWindowSizeClass_mediumWidth_resolvesMediumDualPane() {
        val windowState = WindowSizeClassResolver.resolve(widthDp = 720, heightDp = 1080)
        assertEquals(WindowSizeClass.Medium, windowState.widthSizeClass)
        assertTrue("Medium layout should enable dual pane", windowState.isDualPane)
    }

    @Test
    fun testWindowSizeClass_expandedWidth_resolvesExpandedMultiPane() {
        val windowState = WindowSizeClassResolver.resolve(widthDp = 1024, heightDp = 768)
        assertEquals(WindowSizeClass.Expanded, windowState.widthSizeClass)
        assertTrue("Expanded layout should enable multi-pane", windowState.isDualPane)
    }

    @Test
    fun testWindowSizeClass_dynamicResize_emitsUpdatedStateFlow() = runTest {
        val handler = WindowSizeClassHandler(360, 800)
        handler.windowState.test {
            assertEquals(WindowSizeClass.Compact, awaitItem().widthSizeClass)
            handler.onConfigurationChanged(widthDp = 900, heightDp = 600)
            assertEquals(WindowSizeClass.Expanded, awaitItem().widthSizeClass)
        }
    }

    // -------------------------------------------------------------------------
    // SUITE 2: FoldablePostureHandler Calculations
    // -------------------------------------------------------------------------

    @Test
    fun testFoldablePosture_flat_rendersSingleFlatViewport() {
        val posture = FoldablePostureHandler.calculate(
            isSeparating = false,
            orientation = FoldOrientation.NONE,
            hingeBounds = Rect(0, 0, 0, 0),
            screenBounds = Rect(0, 0, 1080, 2400)
        )
        assertEquals(DevicePosture.Flat, posture)
    }

    @Test
    fun testFoldablePosture_tabletop_splitsTopTerminalAndBottomControls() {
        val posture = FoldablePostureHandler.calculate(
            isSeparating = true,
            orientation = FoldOrientation.HORIZONTAL,
            hingeBounds = Rect(0, 1180, 1080, 1220),
            screenBounds = Rect(0, 0, 1080, 2400)
        )
        assertTrue("Posture must be TableTop", posture is DevicePosture.TableTop)
        val tabletop = posture as DevicePosture.TableTop
        assertEquals(Rect(0, 0, 1080, 1180), tabletop.topPaneBounds)
        assertEquals(Rect(0, 1220, 1080, 2400), tabletop.bottomPaneBounds)
        assertEquals(40, tabletop.hingeOcclusionHeightPx)
    }

    @Test
    fun testFoldablePosture_book_splitsLeftSessionAndRightTerminal() {
        val posture = FoldablePostureHandler.calculate(
            isSeparating = true,
            orientation = FoldOrientation.VERTICAL,
            hingeBounds = Rect(1060, 0, 1100, 1800),
            screenBounds = Rect(0, 0, 2160, 1800)
        )
        assertTrue("Posture must be Book", posture is DevicePosture.Book)
        val book = posture as DevicePosture.Book
        assertEquals(Rect(0, 0, 1060, 1800), book.leftPaneBounds)
        assertEquals(Rect(1100, 0, 2160, 1800), book.rightPaneBounds)
        assertEquals(40, book.hingeOcclusionWidthPx)
    }

    // -------------------------------------------------------------------------
    // SUITE 3: AccessibilityNodeProvider Virtual Tree Queries & Actions
    // -------------------------------------------------------------------------

    @Test
    fun testAccessibilityNodeProvider_createHostNode_returnsValidRootInfo() {
        val view = WarpInputView(activity)
        val provider = view.accessibilityNodeProvider
        assertNotNull("AccessibilityNodeProvider must not be null", provider)
        val hostInfo = provider!!.createAccessibilityNodeInfo(View.NO_ID)
        assertNotNull("Host AccessibilityNodeInfo must not be null", hostInfo)
        assertEquals("dev.warp.mobile.WarpInputView", hostInfo!!.className)
        assertTrue("Host view must be focusable", hostInfo.isFocusable)
    }

    @Test
    fun testAccessibilityNodeProvider_virtualChildNodes_exposesTerminalLines() {
        val provider = WarpAccessibilityNodeProvider(
            hostView = WarpInputView(activity),
            virtualBlocks = listOf(
                VirtualBlockNode(id = 1, text = "ls -la", output = "total 0", bounds = Rect(0, 0, 1080, 100))
            )
        )
        val childInfo = provider.createAccessibilityNodeInfo(1)
        assertNotNull("Virtual node info must exist", childInfo)
        assertEquals("ls -la", childInfo!!.text)
        assertEquals("ls -la\ntotal 0", childInfo.contentDescription)
        assertTrue("Virtual block node must contain ACTION_FOCUS", childInfo.actionList.any { it.id == AccessibilityNodeInfo.ACTION_FOCUS })
    }

    @Test
    fun testAccessibilityNodeProvider_performActionFocus_updatesFocusState() {
        var actionHandled = false
        val provider = WarpAccessibilityNodeProvider(
            hostView = WarpInputView(activity),
            virtualBlocks = listOf(VirtualBlockNode(id = 1, text = "git status", bounds = Rect(0, 0, 1080, 100))),
            onAction = { virtualId, action -> if (virtualId == 1 && action == AccessibilityNodeInfo.ACTION_FOCUS) actionHandled = true }
        )
        val result = provider.performAction(1, AccessibilityNodeInfo.ACTION_FOCUS, null)
        assertTrue("Action focus must return true", result)
        assertTrue("Action callback must be triggered", actionHandled)
    }

    @Test
    fun testAccessibilityNodeProvider_customCopyAction_executesBlockCopy() {
        var copiedContent: String? = null
        val provider = WarpAccessibilityNodeProvider(
            hostView = WarpInputView(activity),
            virtualBlocks = listOf(VirtualBlockNode(id = 1, text = "echo 'hello'", output = "hello")),
            onCopyOutput = { blockId -> copiedContent = "hello" }
        )
        val result = provider.performAction(1, R.id.action_accessibility_copy_output, null)
        assertTrue("Copy action must return true", result)
        assertEquals("hello", copiedContent)
    }

    // -------------------------------------------------------------------------
    // SUITE 4: DeX & Multi-Window Freeform Resizing
    // -------------------------------------------------------------------------

    @Test
    fun testDeXMode_freeformWindowResize_adaptsLayoutColumns() {
        val dexHandler = DeXLayoutHandler()
        val desktopState = dexHandler.onWindowBoundsChanged(widthPx = 1920, heightPx = 1080)
        assertTrue("Desktop mode must enable multi-column split view", desktopState.isMultiColumn)
        assertEquals(3, desktopState.activePaneCount)

        val compactState = dexHandler.onWindowBoundsChanged(widthPx = 600, heightPx = 800, isDeXEnabled = false)
        assertFalse(compactState.isMultiColumn)
        assertEquals(1, compactState.activePaneCount)
    }

    // -------------------------------------------------------------------------
    // SUITE 5: WarpAdaptiveLayout calculation
    // -------------------------------------------------------------------------

    @Test
    fun testWarpAdaptiveLayout_calculation() {
        assertEquals(
            WarpLayoutType.COMPACT_SINGLE_PANE,
            WarpAdaptiveLayout.calculateWarpLayoutType(WindowSizeClass.Compact, DevicePosture.Flat)
        )
        assertEquals(
            WarpLayoutType.MEDIUM_SPLIT_PANE,
            WarpAdaptiveLayout.calculateWarpLayoutType(WindowSizeClass.Medium, DevicePosture.Flat)
        )
        assertEquals(
            WarpLayoutType.EXPANDED_DUAL_PANE,
            WarpAdaptiveLayout.calculateWarpLayoutType(WindowSizeClass.Expanded, DevicePosture.Flat)
        )
        val tabletop = DevicePosture.TableTop(Rect(0,0,100,50), Rect(0,60,100,110), 10)
        assertEquals(
            WarpLayoutType.TABLETOP_POSTURE,
            WarpAdaptiveLayout.calculateWarpLayoutType(WindowSizeClass.Compact, tabletop)
        )
        val book = DevicePosture.Book(Rect(0,0,50,100), Rect(60,0,110,100), 10)
        assertEquals(
            WarpLayoutType.BOOK_POSTURE,
            WarpAdaptiveLayout.calculateWarpLayoutType(WindowSizeClass.Compact, book)
        )
    }

    // -------------------------------------------------------------------------
    // SUITE 6: Compose Semantics & Components
    // -------------------------------------------------------------------------

    @Test
    fun testComposeSemantics_promptComposerMountsSuccessfully() {
        var submittedText: String? = null
        val composeView = ComposeView(activity).apply {
            setContent {
                PromptComposer(
                    value = TextFieldValue("git status"),
                    onValueChange = {},
                    onSubmit = { submittedText = it }
                )
            }
        }
        activity.setContentView(composeView)
        composeView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY)
        )
        composeView.layout(0, 0, 1080, 200)

        assertNotNull("PromptComposer view must be mounted", composeView)
    }
}
