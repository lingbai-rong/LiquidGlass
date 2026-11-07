package rongyi.lingbai;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Six opt-in host bridges reconstructed from LiquidTab's verified navigation symbols.
 * Rendering, settings, capture, suppression snapshots and restoration stay in LiquidGlass.
 */
final class LiquidTabHostAdapter extends UniversalHostAdapter {
    // Values must not retain their Activity keys, including indirectly through host Views.
    private static final Map<Activity, Session> SESSIONS = new WeakHashMap<>();
    private final Session session;

    private static final class Session {
        WeakReference<View> row = new WeakReference<>(null);
        long lastProbe = -1000L;
        boolean dirty = true;
        int selected = -1;
        Bitmap[] icons;
        final List<WeakReference<View>> iconSlots = new ArrayList<>();
    }

    LiquidTabHostAdapter(Activity activity, TargetApp app, TargetAppSettings settings) {
        super(activity, app, settings);
        Session value = SESSIONS.get(activity);
        if (value == null) {
            value = new Session();
            SESSIONS.put(activity, value);
        }
        session = value;
    }

    static void forget(Activity activity) {
        SESSIONS.remove(activity);
    }

    static void invalidate(Activity activity) {
        Session value = SESSIONS.get(activity);
        if (value != null) {
            value.lastProbe = -1000L;
            value.dirty = true;
        }
    }

    private static String resourceName(View view) {
        if (view == null || view.getId() == View.NO_ID) return "";
        try { return view.getResources().getResourceEntryName(view.getId()); }
        catch (Throwable ignored) { return ""; }
    }

    private boolean matchesRow(View view) {
        if (!(view instanceof ViewGroup)) return false;
        String id = resourceName(view);
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (LiquidTabNavigationRules.matchesRow(app, type.getName(), id)) return true;
        }
        return false;
    }

    private View row() {
        View previous = session.row.get();
        long now = SystemClock.uptimeMillis();
        if (previous != null && previous.isAttachedToWindow()
                && previous.isShown() && !session.dirty) return previous;
        if (now - session.lastProbe < 500L) return null;
        session.lastProbe = now;
        session.dirty = false;
        View root = decor();
        if (root == null) return null;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        View found = null;
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 4000) {
            View view = queue.removeFirst();
            if (view instanceof MiuixGlassNavView) continue;
            if (matchesRow(view) && view.isAttachedToWindow()) {
                if (found == null) found = view;
                if (view.isShown()) {
                    found = view;
                    break;
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
        if (found == null && previous != null && previous.isAttachedToWindow()) found = previous;
        if (found != previous) {
            session.row = new WeakReference<>(found);
            session.icons = null;
            session.iconSlots.clear();
            session.selected = -1;
        }
        return found;
    }

    private static boolean descendsFrom(View view, View root) {
        for (int depth = 0; view != null && depth < 24; depth++) {
            if (view == root) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    private static boolean classMatches(View view, String name) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (name.equals(type.getName())) return true;
        }
        return false;
    }

    private List<View> nativeSlots(View row) {
        if (!(row instanceof ViewGroup) || app == TargetApp.BILIBILI_IN) {
            return Collections.emptyList();
        }
        if (app == TargetApp.MI_MARKET || app == TargetApp.MI_COMMUNITY) {
            Object items = getField(row, app == TargetApp.MI_MARKET ? "mTabViews" : "navItems");
            List<View> result = new ArrayList<>();
            if (items instanceof List<?>) {
                for (Object item : (List<?>) items) {
                    if (item instanceof View && descendsFrom((View) item, row)) {
                        result.add((View) item);
                    }
                }
                if (LiquidTabNavigationRules.validSlotCount(app, result.size())) return result;
            }
            result.clear();
            String itemClass = app == TargetApp.MI_MARKET
                    ? LiquidTabNavigationRules.MARKET_ITEM : LiquidTabNavigationRules.COMMUNITY_ITEM;
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(row);
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 180) {
                View view = queue.removeFirst();
                if (classMatches(view, itemClass)) {
                    result.add(view);
                } else if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
                }
            }
            return LiquidTabNavigationRules.validSlotCount(app, result.size())
                    ? result : Collections.emptyList();
        }

        // Only search inside a verified native row, never in the Activity's page content.
        ArrayDeque<ViewGroup> queue = new ArrayDeque<>();
        queue.add((ViewGroup) row);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 80) {
            ViewGroup group = queue.removeFirst();
            if (LiquidTabNavigationRules.validSlotCount(app, group.getChildCount())) {
                List<View> result = new ArrayList<>();
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (!isNavigationSlot(child)) break;
                    result.add(child);
                }
                if (result.size() == group.getChildCount()) return result;
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof ViewGroup) queue.addLast((ViewGroup) child);
            }
        }
        return Collections.emptyList();
    }

    private static boolean isNavigationSlot(View view) {
        if (view.getVisibility() == View.GONE) return false;
        if (view.hasOnClickListeners() || view.isClickable()) return true;
        if (view instanceof ImageView || view instanceof TextView) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (isNavigationSlot(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    @Override public View getBottomBarRoot() {
        View row = row();
        if (row == null) return null;
        if (app == TargetApp.BILIBILI_IN) {
            return row.getWidth() > 0 && row.getHeight() > 0 ? row : null;
        }
        // Leave the original navigation alone when a new host version changes its model.
        return LiquidTabNavigationRules.validSlotCount(app, nativeSlots(row).size()) ? row : null;
    }

    private View compactParent(View row, int levels) {
        View current = row;
        float density = activity.getResources().getDisplayMetrics().density;
        for (int i = 0; current != null && i < levels; i++) {
            View parent = current.getParent() instanceof View ? (View) current.getParent() : null;
            if (parent == null || parent == decor() || parent.getId() == android.R.id.content) break;
            int height = Math.max(parent.getHeight(), parent.getMeasuredHeight());
            // The reference uses these exact ancestor relationships. This extra guard prevents
            // them from ever hiding a full page when a host changes its wrapper hierarchy.
            if (height <= 0 || height > 160f * density) break;
            current = parent;
        }
        return current;
    }

    private View relatedId(View row, String name) {
        View view = findId(name);
        if (view == null || row == null) return null;
        View ancestor = row;
        for (int depth = 0; ancestor != null && depth < 4; depth++) {
            if (descendsFrom(view, ancestor)) return view;
            ancestor = ancestor.getParent() instanceof View ? (View) ancestor.getParent() : null;
        }
        return null;
    }

    @Override public SuppressionPlan getSuppressionPlan() {
        View row = getBottomBarRoot();
        SuppressionPlan plan = new SuppressionPlan();
        if (row == null) return plan;
        if (app == TargetApp.MI_MARKET) {
            plan.offscreen(row);
            View shell = relatedId(row, "tab_container_layout");
            if (shell != null && descendsFrom(row, shell)) plan.clear(shell);
            for (String id : new String[]{"tab_blur_bg", "tab_shadow_bg",
                    "dark_navigation_bar_spacing", "navigation_bar_placeholder"}) {
                View chrome = relatedId(row, id);
                if (chrome != null && !descendsFrom(row, chrome)) plan.offscreen(chrome);
            }
            plan.zeroBottomMargin(relatedId(row, "fragment_container"));
        } else if (app == TargetApp.MI_COMMUNITY) {
            plan.offscreen(row).zeroBottomMargin(relatedId(row, "pager"));
        } else if (app == TargetApp.WEIBO) {
            // Never hide the grandparent TabHost: it also owns the destination pages.
            plan.offscreen(compactParent(row, 1));
        } else if (app == TargetApp.TAOBAO) {
            plan.offscreen(compactParent(row, 2));
            for (String id : new String[]{"uik_navigation_tab_background", "uik_navigation_tab_divide"}) {
                View chrome = relatedId(row, id);
                if (chrome != null && !descendsFrom(row, chrome)) plan.offscreen(chrome);
            }
            plan.zeroBottomMargin(relatedId(row, "tbTabFragment"));
        } else {
            // In particular, retain the ComposeView's size so Bilibili's real touch router works.
            plan.offscreen(row);
        }
        return plan;
    }

    private Object tabController(View row) {
        View current = row;
        for (int depth = 0; current != null && depth < 4; depth++) {
            if (call(current, "getCurrentTab") instanceof Number) return current;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return null;
    }

    private static boolean clickSlot(View slot) {
        if (slot == null || !slot.isEnabled()) return false;
        try { if (slot.performClick()) return true; } catch (Throwable ignored) {}
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(slot);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 64) {
            View view = queue.removeFirst();
            if (view != slot && view.isEnabled() && view.getVisibility() == View.VISIBLE
                    && view.hasOnClickListeners()) {
                try { if (view.performClick()) return true; } catch (Throwable ignored) {}
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
        return false;
    }

    @Override public boolean select(int index) {
        int nativeIndex = nativeForLogical(index);
        View row = getBottomBarRoot();
        if (row == null || nativeIndex < 0 || nativeIndex >= app.labels.length) return false;
        boolean handled = false;
        if (app == TargetApp.WEIBO) {
            handled = callExists(tabController(row), "setCurrentTab", nativeIndex);
        } else if (app == TargetApp.BILIBILI_IN) {
            float x = LiquidTabNavigationRules.slotCenter(nativeIndex, app.labels.length, row.getWidth());
            if (x >= 0f) handled = dispatchTapAt(row, x, row.getHeight() * 0.5f);
        }
        if (!handled && app != TargetApp.BILIBILI_IN) {
            List<View> slots = nativeSlots(row);
            handled = nativeIndex < slots.size() && clickSlot(slots.get(nativeIndex));
            if (!handled && app == TargetApp.MI_MARKET) {
                handled = callExists(row, "show", nativeIndex, -1);
            } else if (!handled && app == TargetApp.MI_COMMUNITY) {
                Object listener = getField(row, "selectedListener");
                if (listener != null && callExists(row, "setCurrentItem", nativeIndex)) {
                    handled = callExists(listener, "onNavigationItemSelected", nativeIndex);
                }
            } else if (!handled && (app == TargetApp.TAOBAO || app == TargetApp.TIEBA)) {
                handled = callExists(tabController(row), "setCurrentTab", nativeIndex);
            }
        }
        if (handled) session.selected = nativeIndex;
        return handled;
    }

    private static int selectedScore(View view, int depth) {
        if (view == null || depth > 5) return 0;
        int score = view.isSelected() ? 4 : view.isActivated() ? 2 : 0;
        if (view instanceof android.widget.Checkable && ((android.widget.Checkable) view).isChecked()) {
            score += 4;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                score += selectedScore(group.getChildAt(i), depth + 1);
            }
        }
        return score;
    }

    @Override public int detectSelectedIndex() {
        View row = getBottomBarRoot();
        if (row == null) return -1;
        int selected = -1;
        if (app == TargetApp.MI_MARKET) {
            selected = intValue(getField(row, "mCurrentTabIndex"), -1);
        } else if (app == TargetApp.MI_COMMUNITY) {
            selected = intValue(call(row, "getSelectPosition"), -1);
        } else if (app != TargetApp.BILIBILI_IN) {
            selected = intValue(call(tabController(row), "getCurrentTab"), -1);
        }
        if (selected < 0 || selected >= app.labels.length) {
            List<View> slots = nativeSlots(row);
            int best = 0;
            boolean tied = false;
            selected = -1;
            for (int i = 0; i < slots.size(); i++) {
                int score = selectedScore(slots.get(i), 0);
                if (score > best) {
                    best = score;
                    selected = i;
                    tied = false;
                } else if (score > 0 && score == best) {
                    tied = true;
                }
            }
            if (tied) selected = -1;
        }
        if (selected >= 0 && selected < app.labels.length) session.selected = selected;
        // LiquidTab also retains the last dispatched selection for the Compose-only row.
        // Unknown state returns -1 rather than inventing a native selection or changing the host.
        return logicalForNative(session.selected);
    }

    @Override public boolean post() { return false; }
    @Override public boolean isNavigationVisible() {
        View row = getBottomBarRoot();
        return row != null && row.isAttachedToWindow() && row.isShown();
    }
    @Override public boolean shouldShowInjectedNavigation() { return isNavigationVisible(); }
    @Override public long backdropTransitionDelayMs() { return 112L; }
    @Override public long backdropMovingCaptureIntervalMs() { return 40L; }
    @Override public long backdropSettleCaptureDelayMs() { return 120L; }
    @Override public boolean preserveLastBackdropOnBlankFrame() { return true; }

    @Override public Bitmap[] getNavigationIconBitmaps() {
        List<View> slots = nativeSlots(row());
        if (slots.isEmpty()) return new Bitmap[0];
        boolean same = slots.size() == session.iconSlots.size();
        for (int i = 0; same && i < slots.size(); i++) {
            same = session.iconSlots.get(i).get() == slots.get(i);
        }
        if (!same) {
            session.iconSlots.clear();
            for (View slot : slots) session.iconSlots.add(new WeakReference<>(slot));
            session.icons = new Bitmap[slots.size()];
        }
        if (session.icons == null) session.icons = new Bitmap[slots.size()];
        Bitmap[] result = new Bitmap[visibleNativeIds.length];
        for (int i = 0; i < visibleNativeIds.length; i++) {
            int nativeIndex = visibleNativeIds[i];
            if (nativeIndex < 0 || nativeIndex >= slots.size()) continue;
            if (session.icons[nativeIndex] == null) {
                session.icons[nativeIndex] = snapshotIcon(slots.get(nativeIndex));
            }
            result[i] = session.icons[nativeIndex];
        }
        return result;
    }

    private static Bitmap snapshotIcon(View slot) {
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(slot);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 64) {
            View view = queue.removeFirst();
            String name = resourceName(view);
            if (view instanceof ImageView && !name.contains("badge")
                    && !name.contains("red_dot") && !name.contains("shadow")) {
                Drawable source = ((ImageView) view).getDrawable();
                if (source != null) {
                    // Copy the Drawable; do not alter the host's checked state, bounds or asset.
                    Drawable.ConstantState state = source.getConstantState();
                    if (state != null) {
                        try {
                            Drawable drawable = state.newDrawable(view.getResources()).mutate();
                            int width = drawable.getIntrinsicWidth();
                            int height = drawable.getIntrinsicHeight();
                            if (width > 0 && height > 0 && width <= height * 3 && height <= width * 3) {
                                int size = 96;
                                Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                                float scale = Math.min((float) size / width, (float) size / height);
                                int w = Math.max(1, Math.round(width * scale));
                                int h = Math.max(1, Math.round(height * scale));
                                drawable.setState(new int[]{android.R.attr.state_enabled,
                                        android.R.attr.state_selected, android.R.attr.state_checked});
                                drawable.setBounds(new Rect((size - w) / 2, (size - h) / 2,
                                        (size + w) / 2, (size + h) / 2));
                                drawable.draw(new Canvas(bitmap));
                                int[] pixels = new int[size * size];
                                bitmap.getPixels(pixels, 0, size, 0, 0, size, size);
                                for (int pixel : pixels) {
                                    if ((pixel >>> 24) > 8) return bitmap;
                                }
                                bitmap.recycle();
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
        return null;
    }
}
