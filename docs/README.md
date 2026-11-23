# Vesper documentation

| Page | What |
| --- | --- |
| [config.schema.json](config.schema.json) | JSON Schema of the config file (regenerated from the config types, T20) |
| [benchmarks/baseline.md](benchmarks/baseline.md) | Baseline benchmark results (re-run in T3) |
| [benchmarks/parser.md](benchmarks/parser.md) | Parser throughput against vte (T4) |
| [benchmarks/grid.md](benchmarks/grid.md) | Bytes per scrollback row and cost of a scrolled line (T6) |
| [benchmarks/term.md](benchmarks/term.md) | Terminal throughput: bytes per second through `Terminal.feed` (T7) |
| [design/](design/) | UI design: one Markdown file per frame of the Figma file [Modern terminal](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal), with components, states, error and offline behaviour. The mockups call the product Orbit; read it as Vesper. [01-main-macos.md](design/01-main-macos.md) describes the shared layout |
| [research/](research/) | Per-terminal research notes: kitty, WezTerm and Alacritty, foot and Contour, Warp, and a core with a native UI (written for a C ABI; its prior art and rendering parts still apply) |
| [site.md](site.md) | Website copy for the pyrlyn project site |

The overview of every terminal studied is [research.md](../research.md). The Kotlin/Native
plan is [PLAN.md](../PLAN.md); work items are in [plan/](../plan/README.md).
