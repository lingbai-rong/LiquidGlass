#!/usr/bin/env python3
"""Offline Kotlin structure, adapter signatures and reference-block checks, NOT a Kotlin build."""
from pathlib import Path
import hashlib
import re

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/rongyi/lingbai"

def masked(s):
    out=list(s); i=0
    def wipe(a,z):
        for k in range(a,z):
            if out[k]!='\n':out[k]=' '
    while i<len(s):
        start=i
        if s.startswith('//',i):
            i=s.find('\n',i)
            if i<0:i=len(s)
        elif s.startswith('/*',i):
            i+=2;level=1
            while i<len(s) and level:
                if s.startswith('/*',i):level+=1;i+=2
                elif s.startswith('*/',i):level-=1;i+=2
                else:i+=1
            assert not level,'Unclosed comment'
        elif s.startswith('"""',i):
            z=s.find('"""',i+3);assert z>=0,'Unclosed raw string';i=z+3
        elif s[i] in '\'"':
            quote=s[i];i+=1
            while i<len(s):
                if s[i]=='\\':i+=2
                elif s[i]==quote:i+=1;break
                else:i+=1
            else:raise AssertionError('Unclosed string')
        else:i+=1;continue
        wipe(start,i)
    return ''.join(out)

def balanced(s):
    stack=[]
    for i,c in enumerate(masked(s)):
        if c in '({[':stack.append((c,i))
        elif c in ')}]':
            assert stack and stack[-1][0]=='({['[')}]'.index(c)],f'Unbalanced {c} at {s[:i].count(chr(10))+1}'
            stack.pop()
    assert not stack,stack

def body(s,name):
    pos=s.index('fun '+name+'(')
    m=masked(s);a=m.index('{',pos);level=1;z=a+1
    while level:
        if m[z]=='{':level+=1
        elif m[z]=='}':level-=1
        z+=1
    return s[a:z]


def block_after(source, marker):
    start = source.index(marker)
    mask = masked(source)
    a = mask.index("{", start)
    z = a + 1
    depth = 1
    while depth:
        if mask[z] == "{": depth += 1
        elif mask[z] == "}": depth -= 1
        z += 1
    return source[a:z]


def digest(text):
    return hashlib.sha256(re.sub(r"\s+", "", text).encode()).hexdigest()


def reference_sections(nav, icons):
    floating = body(nav, "HyperFloatingNavigationBar")
    font = body(nav, "FontNavigationItem")
    standard = body(nav, "HyperNavigation")
    start = standard.index("                            NavigationBarItem(")
    page_item = standard[start:standard.index("\n                        }", start)]
    page_item = page_item.replace("onClick = { onTabClick(index) }",
        "onClick = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); tab.onClick() }")
    return {
        "floating_colors": floating[floating.index("    val contentColor"):floating.index("    val fallbackSurface")],
        "floating_material": floating[floating.index("                .shadow("):floating.index("        Box(Modifier.fillMaxSize().padding(innerPadding))")],
        "floating_label": block_after(floating, 'if (showLabels'),
        "standard_items": page_item,
        "text_item": font,
        "reference_icons": block_after(icons, 'object HyperIcons'),
        "reference_icon_mapping": block_after(icons, 'fun hyperIcon'),
        "reference_strokes": block_after(icons, ').apply {'),
    }

# Hashes computed from the supplied HyperChanger GlassNavigation.kt, NOT from this port.
REFERENCE_HASHES = {'floating_colors': 'ef452b90105f42dfcfd9caf64157dd7847a399aad069bdad3415a74f65e79d77', 'floating_material': '1c13039fae7b6a9730aa1e176f36e4ca93aab499b1bccd78ff072e1b31cd0987', 'floating_label': 'd5b0608d83fa479482efbe0a1742693fbf5d230dbb096ee48928f0e28ffb06ba', 'standard_items': 'cf591a7c41a69a56360d96c4c30685fbdc1daabb97a13fcde24bc753cc3aec41', 'text_item': '93075d0b96345de520093e23ed99a1acee77d049dab37cdab474753da95edd14', 'reference_icons': '7a52cfa3bfdd1441e3e2937d6b417d638256cd0672b7fd9959dc866d30675421', 'reference_icon_mapping': '53ff46555515ceac47f703deabe310d4e327e3f1568946801904451ea7dfb729', 'reference_strokes': 'a0bcd67655ab27f6dc3c9434c0d67ea52b21707b1a9fea348dab7d578d474442'}


def parameters(source, open_paren):
    mask = masked(source)
    depth = 1
    start = open_paren + 1
    i = start
    result = []
    while depth:
        c = mask[i]
        if c in "({[": depth += 1
        elif c in ")}]": depth -= 1
        if (depth == 1 and c == ',') or depth == 0:
            result.append(mask[start:i].strip())
            start = i + 1
        i += 1
    return [v for v in result if v]


def check_adapter_calls(files):
    names = ["InjectedHyperBottomBar", "HyperChangerInjectedNavigation",
             "ReferenceGlassBottomBarHost", "LegacyReferenceGlassBottomBarHost"]
    for name in names:
        declaration = None
        calls = []
        for path, source in files:
            mask = masked(source)
            for match in re.finditer(r"\b" + name + r"\(", mask):
                args = parameters(source, match.end() - 1)
                if re.search(r"fun\s*$", mask[:match.start()]):
                    declaration = args
                else:
                    calls.append((path, args))
        assert declaration is not None, name
        allowed = {re.match(r"(\w+)\s*:", p).group(1) for p in declaration}
        required = {re.match(r"(\w+)\s*:", p).group(1) for p in declaration if '=' not in p}
        assert calls, name
        for path, args in calls:
            supplied = {re.match(r"(\w+)\s*=", a).group(1) for a in args}
            assert required <= supplied <= allowed, (path.name, name, required - supplied, supplied - allowed)
    print("PASS: host adapter named arguments and required parameters match their Kotlin declarations")


def main():
    files = [(p, p.read_text()) for p in SRC.rglob("*.kt")]
    for path, source in files:
        try: balanced(source)
        except AssertionError as error: raise AssertionError(f"{path}: {error}")
        for name in ("BackHandler", "PredictiveBackHandler"):
            if re.search(r"(?<!\w)" + name + r"\(", source):
                assert f"import androidx.activity.compose.{name}" in source, path
    print(f"PASS: {len(files)} Kotlin files have balanced delimiters and explicit BackHandler imports")
    check_adapter_calls(files)
    nav = (SRC / "moduleui/HyperChangerNavigation.kt").read_text()
    icons = (SRC / "moduleui/ReferenceHyperIcons.kt").read_text()
    for name, section in reference_sections(nav, icons).items():
        assert digest(section) == REFERENCE_HASHES[name], name
    print("PASS: reference colors, floating material/typography, standard Miuix items, glyphs and strokes match the supplied HyperChanger source")
    bridge = (SRC / "InjectedHyperBottomBar.kt").read_text()
    for token in ("settings.tabWidthDp", "settings.textSizeSp", "settings.lightAlpha", "settings.darkAlpha", "settings.blurRadiusDp"):
        assert token not in bridge, token
    host_wrapper = body(nav, "HyperChangerInjectedNavigation")
    assert "tabIconContent = iconContent" in host_wrapper
    assert "blurRadius = 18" in host_wrapper
    assert "layout.captureHeightDp.dp" in bridge
    print("PASS: injected HyperOS page presets retain reference sizing and material; captured source uses the shared bar geometry")
    shared = (SRC / "GlassNavShared.kt").read_text()
    old_post_hashes = {
        "rememberGlassPostButtonColors": "7d4c2f56b7349d17f6f15bcbc8f5fa7d3a941c9e6be81036a3f38d0a06b22787",
        "GlassPostButtonIcon": "f59e28f9108f5341a7594da719d4c9ba3244c90b2434301ed99302663dfd389a",
        "GlassOvalPostButtonIcon": "639318c347f67bbd4acad1a59e2289d0ca99d75b25b652cb471ed04528f5a13e",
    }
    for name, expected in old_post_hashes.items():
        assert digest(body(shared, name)) == expected, name
        assert name + "(" in bridge, name
    assert "GlassSeparatePostButton(dark, layout.circleDiameterDp, onPost)" in bridge
    assert nav.count("performHapticFeedback(") == 1
    assert "if (injected == null) view.performHapticFeedback" in nav
    for name in ("MiuixGlassNavView.kt", "CoolapkLegacyGlassNavView.kt"):
        host = (SRC / name).read_text()
        assert host.count("performHapticFeedback(") == 1, name
        assert "if (navigationHapticsEnabled)" in body(host, "performNavigationHaptic"), name
        commit = body(host, "commitSelectedIndex")
        assert commit.index("lastDispatchAt < 320L") < commit.rindex("performNavigationHaptic()"), name
        assert "if (accepted) performNavigationHaptic()" in body(host, "dispatchOriginalPostClick"), name
    main_source = (SRC / "MainActivity.kt").read_text()
    assert "R.string.module_logging" not in body(main_source, "ModuleSettingsScreen")
    assert "R.string.module_logging" in body(main_source, "ModuleDeveloperScreen")
    global_source = (SRC / "GlobalSettingsActivity.kt").read_text()
    dimension_group = block_after(global_source, "if (navigationStyle == InjectedNavigationStyle.LIQUID_GLASS) {\n            GlobalSection")
    assert 'GlobalSection("液态玻璃尺寸"' in dimension_group
    assert dimension_group.count("SliderPreference(") == 4
    assert "onNavigationHapticsChanged(enabled)" in global_source
    print("PASS: original post artwork, shared vibration gate/debounce, conditional size group and developer logging placement")
    print("NOTE: these source checks do not perform Kotlin type checking, Compose rendering, or device verification.")


if __name__ == "__main__":
    main()
