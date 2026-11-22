# Vesper documentation

| Page | What |
| --- | --- |
| [config.schema.json](config.schema.json) | JSON Schema of the config file (regenerated from the config types, T20) |
| [benchmarks/baseline.md](benchmarks/baseline.md) | Baseline benchmark results (re-run in T3) |
| [benchmarks/parser.md](benchmarks/parser.md) | Parser throughput against vte (T4) |
| [benchmarks/macos-renderer.md](benchmarks/macos-renderer.md) | macOS renderer measurements (re-run in T15) |
| [benchmarks/windows-renderer.md](benchmarks/windows-renderer.md) | Windows renderer measurements (re-run in T17) |
| [design/](design/) | UI design: one Markdown file per frame of the Figma file [Modern terminal](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal), with components, states, error and offline behaviour. The mockups call the product Orbit; read it as Vesper. [01-main-macos.md](design/01-main-macos.md) describes the shared layout |
| [research/](research/) | Per-terminal research notes: kitty, WezTerm and Alacritty, foot and Contour, Warp, FFI to native UI |
| [site.md](site.md) | Website copy for the pyrlyn project site |

The overview of every terminal studied is [research.md](../research.md). The Kotlin/Native
rewrite plan is [PLAN.md](../PLAN.md); work items are in [plan/](../plan/README.md).
