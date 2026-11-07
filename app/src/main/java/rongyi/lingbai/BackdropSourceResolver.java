package rongyi.lingbai;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the page that is actually visible inside a host pager/container.
 *
 * ViewPager/ViewPager2 keep neighbouring pages attached. Capturing the pager itself can therefore
 * redraw an old page even though the host has already changed tabs. This helper returns the exact
 * current item when possible and otherwise selects the dominant visible page without touching the
 * host's navigation/controller objects.
 */
final class BackdropSourceResolver {
    private BackdropSourceResolver() {}

    /** Resolve the active Fragment page owned by an Activity/AppCompatActivity. */
    static View primaryFragmentView(Object owner) {
        if (owner == null) return null;
        Object manager = invokeAny(owner, "getSupportFragmentManager", "getFragmentManager");
        Object fragment = primaryFragment(manager);
        return deepestFragmentView(fragment);
    }

    /** Resolve a Fragment already stored in the host's exact tab-to-Fragment map. */
    static View fragmentViewFromMap(Object mapValue, Object key) {
        if (mapValue == null || key == null) return null;
        Object fragment = null;
        try {
            if (mapValue instanceof Map) fragment = ((Map<?, ?>) mapValue).get(key);
        } catch (Throwable ignored) {}
        if (fragment == null) fragment = invoke(mapValue, "get", key);
        return deepestFragmentView(fragment);
    }

    static View deepestFragmentView(Object fragment) {
        if (fragment == null) return null;
        View best = usableView(invoke(fragment, "getView"));
        Object current = fragment;
        Set<Object> seen = new HashSet<>();
        for (int depth = 0; depth < 8 && current != null && seen.add(current); depth++) {
            Object childManager = invoke(current, "getChildFragmentManager");
            Object child = primaryFragment(childManager);
            if (child == null || child == current) break;
            current = child;
            View childView = usableView(invoke(current, "getView"));
            if (childView != null) best = childView;
        }
        return best;
    }

    private static Object primaryFragment(Object manager) {
        if (manager == null) return null;
        Object fragment = invokeAny(
                manager, "getPrimaryNavigationFragment", "getPrimaryNavFragment"
        );
        if (fragment == null) {
            fragment = readAnyField(
                    manager, "mPrimaryNav", "mPrimaryNavigationFragment", "primaryNav", "y"
            );
        }
        if (fragment != null) return fragment;

        // A primary fragment can be absent briefly while a transaction is committing. Prefer the
        // last attached visible Fragment rather than returning a neighbouring/offscreen pager page.
        Object fragments = invokeAny(manager, "getFragments", "getActiveFragments");
        if (fragments instanceof List) {
            List<?> list = (List<?>) fragments;
            for (int i = list.size() - 1; i >= 0; i--) {
                Object candidate = list.get(i);
                if (candidate == null) continue;
                if (usableView(invoke(candidate, "getView")) != null) return candidate;
            }
        }
        return null;
    }

    static View currentPagerPage(View pager, int preferredPosition) {
        if (!isUsable(pager)) return null;
        int position = preferredPosition >= 0 ? preferredPosition : readCurrentPosition(pager);

        View recycler = findRecyclerView(pager);
        if (recycler != null && position >= 0) {
            Object holder = invoke(recycler, "findViewHolderForAdapterPosition", position);
            View item = asView(readField(holder, "itemView"));
            if (isUsable(item)) return dominantVisiblePage(item, null);

            holder = invoke(recycler, "findViewHolderForLayoutPosition", position);
            item = asView(readField(holder, "itemView"));
            if (isUsable(item)) return dominantVisiblePage(item, null);
        }

        if (pager instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) pager;
            View positioned = null;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (!isUsable(child)) continue;
                int childPosition = readLayoutPosition(child);
                if (position >= 0 && childPosition == position) {
                    positioned = child;
                    break;
                }
            }
            if (positioned != null) return dominantVisiblePage(positioned, null);
        }

        View visible = bestVisibleDescendant(pager, null, 220);
        return visible != null ? dominantVisiblePage(visible, null) : dominantVisiblePage(pager, null);
    }

    static View dominantVisiblePage(View root, View excluded) {
        if (!isUsable(root)) return null;
        View current = root;
        for (int depth = 0; depth < 8; depth++) {
            if (!(current instanceof ViewGroup)) break;
            ViewGroup group = (ViewGroup) current;
            View next = bestDirectChild(group, excluded);
            if (next == null) break;
            current = next;
        }
        return isUsable(current) ? current : root;
    }

    private static View bestDirectChild(ViewGroup parent, View excluded) {
        if (!isUsable(parent)) return null;
        Rect parentRect = new Rect();
        if (!parent.getGlobalVisibleRect(parentRect) || parentRect.isEmpty()) return null;
        long parentArea = Math.max(1L, (long) parentRect.width() * parentRect.height());

        View best = null;
        double bestScore = 0d;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (!isUsable(child) || child == excluded || child instanceof MiuixGlassNavView
                    || child instanceof CoolapkLegacyGlassNavView) continue;
            Rect childRect = new Rect();
            if (!child.getGlobalVisibleRect(childRect) || childRect.isEmpty()) continue;
            Rect overlap = new Rect();
            if (!overlap.setIntersect(parentRect, childRect)) continue;
            long overlapArea = (long) overlap.width() * overlap.height();
            double coverage = overlapArea / (double) parentArea;
            double widthCoverage = overlap.width() / (double) Math.max(1, parentRect.width());
            if (coverage < 0.42d || widthCoverage < 0.62d) continue;

            float centerDistance = Math.abs(childRect.exactCenterX() - parentRect.exactCenterX())
                    / Math.max(1f, parentRect.width());
            double score = coverage * 1000d + widthCoverage * 120d
                    - centerDistance * 180d + i * 0.05d + child.getTranslationZ() * 0.01d;
            if (score > bestScore) {
                bestScore = score;
                best = child;
            }
        }
        return best;
    }

    private static View bestVisibleDescendant(View root, View excluded, int maxVisited) {
        if (!isUsable(root)) return null;
        Rect rootRect = new Rect();
        if (!root.getGlobalVisibleRect(rootRect) || rootRect.isEmpty()) return null;
        long rootArea = Math.max(1L, (long) rootRect.width() * rootRect.height());

        ArrayDeque<View> views = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        Set<View> seen = new HashSet<>();
        views.add(root);
        depths.add(0);
        View best = null;
        double bestScore = -1d;
        int visited = 0;
        while (!views.isEmpty() && visited++ < maxVisited) {
            View candidate = views.removeFirst();
            int depth = depths.removeFirst();
            if (candidate == null || !seen.add(candidate) || candidate == excluded
                    || !isUsable(candidate) || candidate instanceof MiuixGlassNavView
                    || candidate instanceof CoolapkLegacyGlassNavView) continue;

            Rect rect = new Rect();
            if (candidate.getGlobalVisibleRect(rect) && !rect.isEmpty()) {
                Rect overlap = new Rect();
                if (overlap.setIntersect(rootRect, rect)) {
                    long area = (long) overlap.width() * overlap.height();
                    double coverage = area / (double) rootArea;
                    double widthCoverage = overlap.width() / (double) Math.max(1, rootRect.width());
                    float centerDistance = Math.abs(rect.exactCenterX() - rootRect.exactCenterX())
                            / Math.max(1f, rootRect.width());
                    if (coverage >= 0.42d && widthCoverage >= 0.62d) {
                        double score = coverage * 1000d + depth * 4d - centerDistance * 180d;
                        if (score > bestScore) {
                            bestScore = score;
                            best = candidate;
                        }
                    }
                }
            }

            if (candidate instanceof ViewGroup && depth < 10) {
                ViewGroup group = (ViewGroup) candidate;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    views.addLast(group.getChildAt(i));
                    depths.addLast(depth + 1);
                }
            }
        }
        return best;
    }

    private static int readCurrentPosition(View pager) {
        Object value = invoke(pager, "getCurrentItem");
        int position = asInt(value, -1);
        if (position >= 0) return position;
        position = asInt(readField(pager, "mCurItem"), -1);
        if (position >= 0) return position;
        position = asInt(readField(pager, "currentItem"), -1);
        if (position >= 0) return position;
        return asInt(readField(pager, "mCurrentItem"), -1);
    }

    private static int readLayoutPosition(View child) {
        Object params = child == null ? null : child.getLayoutParams();
        int position = asInt(readField(params, "position"), -1);
        if (position >= 0) return position;
        position = asInt(readField(params, "mPosition"), -1);
        if (position >= 0) return position;
        position = asInt(readField(params, "a"), -1);
        if (position >= 0) return position;
        Object tag = child == null ? null : child.getTag();
        return tag instanceof Integer ? (Integer) tag : -1;
    }

    private static View findRecyclerView(View root) {
        if (!isUsable(root)) return null;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 80) {
            View view = queue.removeFirst();
            String name = view.getClass().getName();
            if (name.endsWith("RecyclerView") || name.contains("RecyclerView")) return view;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
        return null;
    }

    private static boolean isUsable(View view) {
        return view != null && view.isAttachedToWindow() && view.getVisibility() == View.VISIBLE
                && view.isShown() && view.getAlpha() > 0.01f
                && view.getWidth() > 0 && view.getHeight() > 0;
    }

    private static View asView(Object value) {
        return value instanceof View ? (View) value : null;
    }

    private static View usableView(Object value) {
        View view = asView(value);
        return isUsable(view) ? view : null;
    }

    private static int asInt(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static Object readField(Object target, String name) {
        if (target == null || name == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object readAnyField(Object target, String... names) {
        if (names == null) return null;
        for (String name : names) {
            Object value = readField(target, name);
            if (value != null) return value;
        }
        return null;
    }

    private static Object invoke(Object target, String name, Object... args) {
        if (target == null || name == null) return null;
        int count = args == null ? 0 : args.length;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            Method[] methods;
            try { methods = type.getDeclaredMethods(); }
            catch (Throwable ignored) { continue; }
            for (Method method : methods) {
                if (!name.equals(method.getName()) || method.getParameterTypes().length != count) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(target, args);
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Object invokeAny(Object target, String... names) {
        if (names == null) return null;
        for (String name : names) {
            Object value = invoke(target, name);
            if (value != null) return value;
        }
        return null;
    }
}
