package rongyi.lingbai;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public final class UuComposeNavigationLayoutTest {
    public interface TargetModifier {}
    public interface Measure { Object b(Object scope, Object measurable, Object constraints); }
    public interface Placement { Object invoke(Object placementScope); }
    public interface Scope { Result v0(int width, int height, Map<?, ?> lines, Placement placement); }
    private static final Object UNIT = new Object();
    private static final TargetModifier EMPTY = new TargetModifier() {};

    public static final class TargetLayout {
        public static TargetModifier b(TargetModifier modifier, Measure measure) {
            return new Modified(modifier, measure);
        }
    }

    public static final class WrongLayout {
        public static Object b(TargetModifier modifier, Measure measure) { return modifier; }
    }

    private static final class Modified implements TargetModifier {
        final TargetModifier original;
        final Measure measure;
        Modified(TargetModifier original, Measure measure) {
            this.original = original;
            this.measure = measure;
        }
    }

    private static final class Result {
        final int width;
        final int height;
        final Map<?, ?> lines;
        final Placement placement;
        Result(int width, int height, Map<?, ?> lines, Placement placement) {
            this.width = width;
            this.height = height;
            this.lines = lines;
            this.placement = placement;
        }
    }

    private static UuComposeNavigationLayout create() {
        return UuComposeNavigationLayout.create(TargetModifier.class, TargetLayout.class,
                Measure.class, Scope.class, Placement.class, UNIT);
    }

    @Test public void zeroHeightResultDoesNotMeasureOrPlaceNativeChildren() {
        UuComposeNavigationLayout helper = create();
        assertNotNull(helper);
        Modified modifier = (Modified) helper.append(EMPTY);
        Scope scope = Result::new;
        Object forbidden = new Object() {
            @Override public String toString() { throw new AssertionError("native subtree accessed"); }
        };
        Result result = (Result) modifier.measure.b(scope, forbidden, forbidden);
        assertEquals(0, result.width);
        assertEquals(0, result.height);
        assertTrue(result.lines.isEmpty());
        assertSame(UNIT, result.placement.invoke(forbidden));
        assertSame(EMPTY, modifier.original);
    }

    @Test public void callbacksHaveStableIdentityAcrossRecomposition() {
        UuComposeNavigationLayout helper = create();
        Modified first = (Modified) helper.append(EMPTY);
        Modified second = (Modified) helper.append(EMPTY);
        assertSame(first.measure, second.measure);
        assertEquals(first.measure, second.measure);
        assertFalse(first.measure.equals(EMPTY));
        assertFalse(first.measure.equals(null));
        assertEquals(System.identityHashCode(first.measure), first.measure.hashCode());
        Scope scope = Result::new;
        Result one = (Result) first.measure.b(scope, null, null);
        Result two = (Result) second.measure.b(scope, null, null);
        assertSame(one.placement, two.placement);
        assertEquals(one.placement, two.placement);
        assertEquals(System.identityHashCode(one.placement), one.placement.hashCode());
        assertNotNull(one.placement.toString());
    }

    @Test public void incompatibleTargetContractCannotInstallModifier() {
        assertNull(UuComposeNavigationLayout.create(null));
        assertNull(UuComposeNavigationLayout.create(new ClassLoader(null) {}));
        assertNull(UuComposeNavigationLayout.create(TargetModifier.class, WrongLayout.class,
                Measure.class, Scope.class, Placement.class, UNIT));
        assertNull(UuComposeNavigationLayout.create(TargetModifier.class, TargetLayout.class,
                Measure.class, Object.class, Placement.class, UNIT));
        assertNull(UuComposeNavigationLayout.create(TargetModifier.class, TargetLayout.class,
                Measure.class, Scope.class, Placement.class, null));
        assertNull(create().append(new Object()));
        assertNull(create().append(null));
    }

    @Test public void scopeOnlyClaimsFirstMatchingComposerRoot() {
        UuComposeNavigationLayout.Scope scope = new UuComposeNavigationLayout.Scope();
        Object composer = new Object();
        scope.enter(composer);
        assertFalse(scope.claimRoot(new Object(), EMPTY, EMPTY));
        assertTrue(scope.claimRoot(composer, EMPTY, EMPTY));
        assertFalse(scope.claimRoot(composer, EMPTY, EMPTY));
        scope.exit();
    }

    @Test public void unexpectedFirstModifierCannotRedirectSuppressionToAChild() {
        UuComposeNavigationLayout.Scope scope = new UuComposeNavigationLayout.Scope();
        Object composer = new Object();
        scope.enter(composer);
        assertFalse(scope.claimRoot(composer, new Object(), EMPTY));
        assertFalse(scope.claimRoot(composer, EMPTY, EMPTY));
        scope.exit();
    }

    @Test public void nestedScopesRestoreOuterFrameAndCleanUpOnExit() {
        UuComposeNavigationLayout.Scope scope = new UuComposeNavigationLayout.Scope();
        Object outer = new Object();
        Object inner = new Object();
        scope.enter(outer);
        scope.enter(inner);
        assertFalse(scope.claimRoot(outer, EMPTY, EMPTY));
        assertTrue(scope.claimRoot(inner, EMPTY, EMPTY));
        scope.exit();
        assertFalse(scope.claimRoot(inner, EMPTY, EMPTY));
        assertTrue(scope.claimRoot(outer, EMPTY, EMPTY));
        scope.exit();
        assertFalse(scope.claimRoot(outer, EMPTY, EMPTY));
        scope.exit();
        scope.enter(outer);
        assertTrue(scope.claimRoot(outer, EMPTY, EMPTY));
        scope.exit();
    }
}
