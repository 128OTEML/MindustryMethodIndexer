# Mindustry Method Index

Auto-generated Java code index for Mindustry + Arc.
Updated by GitHub Actions when source changes.

## Layout

- index.json                       master index (modules -> packages)
- README.txt                       this file

Per module (e.g. `mindustry-core/`, `arc-core/`, `arc-extensions-fx/`):
- index.json                       module directory (list of packages)
- sig/<pkg>.txt                    compact signatures per Java package
- calls/<pkg>.txt                  outgoing calls (method -> targets)
- callers/<pkg>.txt                incoming calls (target <- callers)
- json/<pkg>.json                  full structured data
- src-root.txt                     source root (internal, do not read)

Derived indexes under `_enhanced/`:
- summary.json                     global counts
- hotspots.txt                     top 200 methods by in-degree
- hotspots.json                    top 500, target + inDegree only
- hierarchy/<FQN>.txt              type hierarchy (parent -> direct children)
- iface/<FQN>.txt                  interface -> implementors
- deps/<pkg>.txt                   package-level dependency
- fields/<pkg>.txt                 field usage
- chains/<target>.txt              call chains (depth 1 and 2)
- inline/<pkg>.txt                 short method bodies (<=10 lines)

## Naming conventions

- `<FQN>`: fully-qualified type name, e.g. `mindustry_gen_Posc`
  (dots replaced with underscores to form a safe file name)
- `<pkg>`: Java package name, e.g. `mindustry.world.blocks`
- `deps/` and `fields/` are keyed by JAVA PACKAGE, not module name.
  Example: to read dependencies of Mindustry core, open
  `_enhanced/deps/mindustry_core.txt` (the mindustry.core package),
  NOT any file named after the module.

## How to read

- Type hierarchy:     `_enhanced/hierarchy/mindustry_gen_Posc.txt`
- Interface impls:    `_enhanced/iface/arc_Application.txt`
- Package deps:       `_enhanced/deps/mindustry_core.txt`
- Hotspots (top API): `_enhanced/hotspots.txt`
- Signatures:         `mindustry-core/sig/mindustry.world.blocks.txt`
- Who calls X:        `mindustry-core/callers/mindustry.world.txt`
- Short method bodies: `_enhanced/inline/arc_util.txt`

## Module list

Mindustry: core, desktop, server, tools, annotations
Arc:       arc-core, extensions/*, backends/backend-sdl, backend-sdl3, backend-headless

Module output names use dashes: `mindustry-core`, `arc-core`,
`arc-extensions-freetype`, `arc-backends-sdl`, etc.
