package rongyi.lingbai.liquidtab.hook.adapters.netease

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import rongyi.lingbai.NativeMusicPlayerGlass
import rongyi.lingbai.XposedHelpers
import rongyi.lingbai.liquidtab.hook.GlassHostLayout
import rongyi.lingbai.liquidtab.hook.PortChromeOwnership
import rongyi.lingbai.liquidtab.hook.adapterRootView
import rongyi.lingbai.liquidtab.hook.adapters.*
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Native mini-player remains the content and input owner, including secondary RN pages. */
internal class NeteaseNavigationState(private val context: Context) : AppNavigationState {
    private var host: WeakReference<GlassHostLayout>? = null
    private var player: WeakReference<ViewGroup>? = null
    private var lastProbe = 0L
    private var heartbeat = false
    private var drawer = false
    private val translated = WeakHashMap<View, Float>()
    private val clips = WeakHashMap<ViewGroup, Pair<Boolean,Boolean>>()
    private fun named(root: View, name: String): View? {
        val id = context.resources.getIdentifier(name, "id", NETEASE_PACKAGE)
        return if (id == 0) null else root.findViewById(id)
    }
    override val allowsSoftwareBackdropCapture = false
    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? =
        source?.takeIf { it.javaClass.name == NETEASE_NAVIGATION_TAB_LAYOUT_CLASS }?.let {
            runCatching { XposedHelpers.callMethod(it,"getSelectedTabPosition") as? Int }.getOrNull()
        }
    override fun resolveBackdropScene(host: View): View? = named(host.adapterRootView,"mainActivityViewPager")
        ?: (host.adapterRootView.findViewById<View>(android.R.id.content) as? ViewGroup)?.let { content ->
            (0 until content.childCount).map(content::getChildAt).firstOrNull { it !is GlassHostLayout }
        }
    override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?): Boolean =
        !drawer && host.adapterNavigationSource?.javaClass?.name == NETEASE_NAVIGATION_TAB_LAYOUT_CLASS
    override fun onHostPreDraw(host: View, source: ViewGroup?, selected: () -> Int, enabled: Boolean,
        density: Float, barHeightPx: Int, surfaceColor: () -> Int) {
        val current = host as? GlassHostLayout ?: return
        this.host = WeakReference(current)
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        if (now-lastProbe < NETEASE_HEARTBEAT_PROBE_INTERVAL_MS) return
        lastProbe = now
        val root = current.adapterRootView
        val queue = ArrayDeque<View>(); queue.add(root)
        var visited = 0
        heartbeat = false; drawer = false
        var found: ViewGroup? = null
        while (queue.isNotEmpty() && visited++ < 4000) {
            val view = queue.removeFirst()
            if (view is GlassHostLayout) continue
            val name = AdapterNavigationSearch.resourceEntryName(view)
            if (selected() == 0 && view is TextView && view.text.toString() == NETEASE_HOME_HEARTBEAT_TAB_TEXT && (view.isSelected || view.isActivated)) heartbeat = true
            if (view.javaClass.name.endsWith("DrawerLayout")) {
                drawer = runCatching { XposedHelpers.callMethod(view,"isDrawerOpen",Gravity.START) as? Boolean }.getOrNull() == true
            }
            if (name in NETEASE_BOTTOM_DECORATION_IDS || name in NETEASE_MINI_PLAYER_DECORATION_IDS) PortChromeOwnership.hide(view)
            if (view.javaClass.name in setOf("com.netease.cloudmusic.module.minibar.widget.VipRenewPopupMinibarGuideView","com.netease.cloudmusic.ui.ad.dslview.common.AdMinibarGuideDSLView")) {
                PortChromeOwnership.hide(view); NeteasePromoVisibilityGuard.hideAdContainer(view)
            }
            if (name in NETEASE_MINI_PLAYER_NATIVE_BG_IDS) {
                PortChromeOwnership.clearBackground(view); PortChromeOwnership.clearForeground(view)
            }
            if (view is ViewGroup && name in NETEASE_MINI_PLAYER_VIEW_NAMES && view.isAttachedToWindow &&
                view.width > root.width/2 && view.height/density in 28f..120f && (found == null || view.height < found.height)) found = view
            if (view is ViewGroup) for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
        }
        val native = found ?: player?.get()?.takeIf { it.isAttachedToWindow } ?: return
        if (player?.get() !== native) { player?.get()?.let(NativeMusicPlayerGlass::remove); player = WeakReference(native) }
        if (heartbeat || drawer) { PortChromeOwnership.hide(native); return }
        PortChromeOwnership.showIfOwned(native)
        if (!native.isShown) return
        NativeMusicPlayerGlass.attach(native) { resolveBackdropScene(current) }
        if (source?.javaClass?.name == NETEASE_NAVIGATION_TAB_LAYOUT_CLASS && current.barVisibilityTarget) {
            translated.putIfAbsent(native,native.translationY)
            var ancestor = native.parent as? ViewGroup
            repeat(6) {
                val group = ancestor ?: return@repeat
                clips.putIfAbsent(group, group.clipChildren to group.clipToPadding)
                group.clipChildren = false; group.clipToPadding = false
                ancestor = group.parent as? ViewGroup
            }
            val a = IntArray(2); val b = IntArray(2); current.getLocationOnScreen(a); native.getLocationOnScreen(b)
            val wantedBottom = a[1]+current.outerRect.top-(NETEASE_MINI_PLAYER_GAP_DP*density)
            native.translationY += wantedBottom-b[1]-native.height
        }
    }
    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        for ((visual,index) in frame.enabledIndices.withIndex()) drawFixedNavigationSlot(canvas,index,frame.selectedIndex,
            frame.darkGlass, (visual+0.5f)*frame.slotWidth, frame.height/2f, 24f*frame.density)
    }
    override fun drawFixedNavigationSlot(canvas: Canvas,index: Int,selectedIndex: Int,dark: Boolean,centerX: Float,centerY: Float,boxSize: Float): Boolean {
        val names = if (index == selectedIndex) NETEASE_SELECTED_ICON_NAMES else NETEASE_NORMAL_ICON_NAMES
        val id = context.resources.getIdentifier(names.getOrNull(index) ?: return false,"drawable",NETEASE_PACKAGE)
        if (id == 0) return false
        val drawable = runCatching { context.getDrawable(id)?.mutate() }.getOrNull() ?: return false
        drawable.setTint(if (index == selectedIndex) NETEASE_ACCENT_COLOR else if (dark) Color.WHITE else Color.BLACK)
        drawable.setBounds((centerX-boxSize/2).toInt(),(centerY-boxSize/2).toInt(),(centerX+boxSize/2).toInt(),(centerY+boxSize/2).toInt())
        drawable.draw(canvas); return true
    }
    override fun dispatchNativeNavigationTap(host: GlassHostLayout,source: ViewGroup?,index: Int,slotCount: Int): Boolean {
        source ?: return false
        return runCatching { val tab = XposedHelpers.callMethod(source,"getTabAt",index) ?: return false; XposedHelpers.callMethod(source,"selectTab",tab); true }.getOrDefault(false)
    }
    override fun dispose() {
        player?.get()?.let(NativeMusicPlayerGlass::remove)
        for ((view,translation) in translated) view.translationY = translation
        for ((group,original) in clips) { group.clipChildren = original.first; group.clipToPadding = original.second }
        translated.clear(); clips.clear(); player = null; host = null
    }
}
