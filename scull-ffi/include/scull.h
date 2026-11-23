/*
 * The C ABI of the scull core.
 *
 * Written by hand and the source of truth: the Kotlin exports compile against this file through
 * cinterop (scull-ffi/src/nativeInterop/cinterop/scull.def), so a layout that drifts from it does
 * not build. The C# bindings are generated from it (`just bindings`).
 *
 * Rules:
 * - Every function is safe to call from any thread with respect to the core's own threads. Calls on
 *   one `tt_term` may come from several host threads; the core serializes them on the terminal's
 *   lock. A `tt_frame` is used by one thread at a time.
 * - Every struct a host passes or receives starts with `struct_size`: the core reads or writes only
 *   `min(struct_size, sizeof)` bytes, so fields are only ever appended.
 * - Constants and enum values are append-only; skip an event kind you do not know.
 * - Text is UTF-8 with an explicit length, never NUL-terminated. Structs hold no `bool`.
 * - An exception inside the core never crosses this boundary: the call answers `TT_PANIC` and the
 *   terminal is poisoned, so every later call on it answers `TT_POISONED` until `tt_term_free`.
 */

#ifndef SCULL_H
#define SCULL_H

#include <stddef.h>
#include <stdint.h>

// Breaking changes bump the major version; a host refuses to run on another major.
#define TT_ABI_VERSION_MAJOR 0

// Additions (new functions, fields appended to a struct) bump the minor. While the major is 0 every
// minor may break, so the minor must match too.
#define TT_ABI_VERSION_MINOR 5

// The version a host was built against, `major << 16 | minor`; pass it in
// `tt_term_options.abi_version`.
#define TT_ABI_VERSION 5

// `tt_event.kind`: BEL arrived. Bells between two polls arrive as one.
#define TT_EVENT_BELL 1

// `tt_event.kind`: the child ended; `exit_code` and `signaled` say how. The last event of a
// terminal.
#define TT_EVENT_CHILD_EXITED 2

// `tt_event.kind`: the program set a title (OSC 0, 1 or 2). `detail` is a `TT_TITLE_*`; the text is
// part `TT_EVENT_TEXT_BODY`.
#define TT_EVENT_TITLE 3

// `tt_event.detail` of `TT_EVENT_TITLE`: both the icon and the window title (OSC 0).
#define TT_TITLE_BOTH 0

// The icon title (OSC 1).
#define TT_TITLE_ICON 1

// The window title (OSC 2).
#define TT_TITLE_WINDOW 2

// `tt_event.kind`: the shell's working directory changed (OSC 7). The `file://` URI is part
// `TT_EVENT_TEXT_BODY`.
#define TT_EVENT_DIRECTORY 4

// `tt_event.kind`: a shell integration mark (OSC 133). `detail` is a `TT_SHELL_*`; for
// `TT_SHELL_COMMAND_FINISHED`, `exit_code` is the command's, or `TT_EXIT_CODE_UNKNOWN` when the
// shell sent none.
#define TT_EVENT_SHELL_MARK 5

// `tt_event.kind`: the program writes the clipboard (OSC 52). The data, decoded and not necessarily
// UTF-8, is part `TT_EVENT_TEXT_BODY`; the selections it names are part `TT_EVENT_TEXT_TITLE`. The
// host decides whether a program may write the clipboard at all.
#define TT_EVENT_CLIPBOARD_WRITE 6

// `tt_event.kind`: the program asks for the clipboard (OSC 52). The selections are part
// `TT_EVENT_TEXT_TITLE`. Answer with `tt_term_clipboard_reply` or `tt_term_clipboard_deny`: the
// program waits for one of them.
#define TT_EVENT_CLIPBOARD_READ 7

// `tt_event.kind`: cells now carry a link id the host has not seen (OSC 8). `id` is the link id, its
// URI part `TT_EVENT_TEXT_BODY`. An id the core freed may come back with another URI in a new event.
#define TT_EVENT_LINK 8

// `tt_event.kind`: a desktop notification (OSC 9, OSC 777 `notify`). The body is part
// `TT_EVENT_TEXT_BODY`, the title part `TT_EVENT_TEXT_TITLE`, empty for OSC 9.
#define TT_EVENT_NOTIFICATION 9

// `tt_event.detail` of `TT_EVENT_SHELL_MARK`: the prompt starts (A).
#define TT_SHELL_PROMPT_START 0

// The prompt ends and the command line starts (B).
#define TT_SHELL_COMMAND_START 1

// The command runs; its output follows (C).
#define TT_SHELL_COMMAND_EXECUTED 2

// The command finished (D).
#define TT_SHELL_COMMAND_FINISHED 3

// `tt_term_event_text` part: the event's main text.
#define TT_EVENT_TEXT_BODY 0

// `tt_term_event_text` part: the second text, a notification's title or the selections a clipboard
// event names (xterm's letters `c p q s 0`-`7`).
#define TT_EVENT_TEXT_TITLE 1

// `tt_event.exit_code` when the system could not tell.
#define TT_EXIT_CODE_UNKNOWN 4294967295u

// `tt_cell.flags`: the cell holds a cluster of several code points; `codepoint` is its first and
// the run's text has all of it.
#define TT_CELL_CLUSTER 1

// `tt_cell.flags`: the cell is selected.
#define TT_CELL_SELECTED 2

// `tt_cell.flags`: the cell is part of a search match.
#define TT_CELL_MATCH 4

// `tt_cell.flags`: the cell is part of the match `tt_term_search_step` went to last; `TT_CELL_MATCH`
// is set too.
#define TT_CELL_CURRENT_MATCH 8

// `tt_term_select_start` kinds: cell by cell, for a drag.
#define TT_SELECT_CELL 0

// Whole words, for a double click.
#define TT_SELECT_WORD 1

// Whole lines, soft wraps included, for a triple click.
#define TT_SELECT_LINE 2

// A rectangle of columns.
#define TT_SELECT_BLOCK 3

// `tt_term_search_set` flag: fold case.
#define TT_SEARCH_IGNORE_CASE 1

// The longest pattern, in bytes, `tt_term_search_set` takes.
#define TT_MAX_SEARCH_PATTERN 256

// The most matches `tt_term_search_count` counts.
#define TT_MAX_SEARCH_MATCHES 10000

// The most bytes `tt_term_selection_text` hands out; longer text is cut on a character boundary.
#define TT_MAX_SELECTION_BYTES 16777216

// `tt_style` colours: the terminal's default colour.
#define TT_COLOR_DEFAULT 0

// A palette entry: the low byte is its index.
#define TT_COLOR_INDEXED 16777216u

// A direct colour: `0xRRGGBB` in the low three bytes.
#define TT_COLOR_RGB 33554432u

// Masks the kind (`TT_COLOR_DEFAULT`, `TT_COLOR_INDEXED`, `TT_COLOR_RGB`) out of a colour.
#define TT_COLOR_KIND_MASK 4278190080u

// `tt_style.attrs` bits.
#define TT_ATTR_BOLD 1
#define TT_ATTR_DIM 2
#define TT_ATTR_ITALIC 4
#define TT_ATTR_BLINK 8
#define TT_ATTR_INVERSE 16
#define TT_ATTR_HIDDEN 32
#define TT_ATTR_STRIKE 64
#define TT_ATTR_OVERLINE 128

// `tt_style.underline` values.
#define TT_UNDERLINE_NONE 0
#define TT_UNDERLINE_SINGLE 1
#define TT_UNDERLINE_DOUBLE 2
#define TT_UNDERLINE_CURLY 3
#define TT_UNDERLINE_DOTTED 4
#define TT_UNDERLINE_DASHED 5

// `tt_key_event.action`: the key went down.
#define TT_KEY_PRESS 1
// The key auto-repeats.
#define TT_KEY_REPEAT 2
// The key went up.
#define TT_KEY_RELEASE 3

// `tt_key_event.mods` and `tt_mouse_event.mods` bits, kitty's.
#define TT_MOD_SHIFT 1
// Alt, Option on macOS.
#define TT_MOD_ALT 2
#define TT_MOD_CTRL 4
// Command on macOS, the Windows key.
#define TT_MOD_SUPER 8
#define TT_MOD_HYPER 16
#define TT_MOD_META 32
// Caps Lock is on.
#define TT_MOD_CAPS_LOCK 64
// Num Lock is on.
#define TT_MOD_NUM_LOCK 128

// `tt_key_event.key` codes for the keys that type no character: kitty's functional key codes.
// Escape; then, one code each, Enter, Tab, Backspace, Insert, Delete, Left, Right, Up, Down, Page Up,
// Page Down, Home and End (`TT_KEY_END`).
#define TT_KEY_ESCAPE 57344
#define TT_KEY_END 57357
// Caps Lock; then Scroll Lock, Num Lock, Print Screen, Pause, Menu.
#define TT_KEY_CAPS_LOCK 57358
// F1; F`n` is `TT_KEY_F1 + n - 1` up to F35.
#define TT_KEY_F1 57364
// Keypad 0; then 1 to 9, Decimal, Divide, Multiply, Subtract, Add, Enter, Equal, Separator, Left,
// Right, Up, Down, Page Up, Page Down, Home, End, Insert, Delete, Begin.
#define TT_KEY_KP_0 57399
// Play; then Pause, Play/Pause, Reverse, Stop, Fast Forward, Rewind, Next Track, Previous Track,
// Record, Volume Down, Volume Up, Mute.
#define TT_KEY_MEDIA_PLAY 57428
// Left Shift on its own; then Left Control, Left Alt, Left Super, Left Hyper, Left Meta, the same six
// on the right, ISO Level 3 Shift and ISO Level 5 Shift.
#define TT_KEY_LEFT_SHIFT 57441

// `tt_mouse_event.action`: a button went down; wheel steps are presses.
#define TT_MOUSE_PRESS 1
// A button went up.
#define TT_MOUSE_RELEASE 2
// The pointer moved to another cell, or another pixel while the program asks for pixels; send it
// only then.
#define TT_MOUSE_MOTION 3

// `tt_mouse_event.button`: none, for motion with no button held. The others are X11's numbers: 1
// left, 2 middle, 3 right, 4 to 7 wheel up, down, left and right, 8 back, 9 forward, 10 and 11.
#define TT_MOUSE_NONE 0
#define TT_MOUSE_LEFT 1
#define TT_MOUSE_MIDDLE 2
#define TT_MOUSE_RIGHT 3
#define TT_MOUSE_WHEEL_UP 4
#define TT_MOUSE_WHEEL_DOWN 5
#define TT_MOUSE_WHEEL_LEFT 6
#define TT_MOUSE_WHEEL_RIGHT 7

// What a call reports. Passed as a plain `int32_t`, so a value added later is one an older host can
// still hold.
enum tt_status
#if defined(__cplusplus) || __STDC_VERSION__ >= 202311L
  : int32_t
#endif
{
  // Done.
  TT_OK = 0,
  // A `NULL` handle or pointer, a `struct_size` too small, a size of zero, or text that is not UTF-8.
  TT_INVALID = 1,
  // The host was built against another ABI version.
  TT_ABI_MISMATCH = 2,
  // An earlier call threw inside this terminal; only `tt_term_free` is left to call.
  TT_POISONED = 3,
  // This call threw inside the core; the terminal is now poisoned.
  TT_PANIC = 4,
  // Nothing to report: the event queue is empty, or the event text asked for is no longer held.
  TT_EMPTY = 5,
  // The terminal has no child to talk to: it was made by `tt_term_new`, or its child has gone.
  TT_CLOSED = 6,
  // The operating system refused: no PTY, a program that does not start, a size the PTY rejects.
  TT_IO = 7,
  // The buffer is too small; nothing was copied.
  TT_FULL = 8,
};
// cinterop takes the enum's constants and would declare the type twice.
#if !defined(__cplusplus) && !defined(TT_TYPES_ONLY)
#if __STDC_VERSION__ >= 202311L
typedef enum tt_status tt_status;
#else
typedef int32_t tt_status;
#endif
#endif

// A UI-owned frame. Opaque to the host; one per view, used from one thread at a time.
typedef struct tt_frame tt_frame;

// One terminal. Opaque to the host.
typedef struct tt_term tt_term;

// UTF-8 text the host owns: `len` bytes at `ptr`, no terminator. `ptr` may be `NULL` when `len` is 0.
typedef struct tt_str {
  const uint8_t *ptr;
  size_t len;
} tt_str;

// Called with the `userdata` given at spawn when the terminal has something new: output to draw or
// an event to poll. It runs on a core thread and must not block, unwind or call any `tt_*`
// function: post a redraw to the UI thread and return. It is not called again until the host calls
// `tt_frame_update` or `tt_term_poll_event` on the terminal, and never after `tt_term_free` returns.
typedef void (*tt_wakeup_fn)(void *userdata);

// One event. A `uint32_t` kind rather than an enum, so a kind added later is a value an older host
// skips, not undefined behaviour.
typedef struct tt_event {
  // `sizeof(tt_event)` as the host knows it.
  uint32_t struct_size;
  // One of `TT_EVENT_*`; skip kinds you do not know.
  uint32_t kind;
  // `TT_EVENT_CHILD_EXITED` and a finished command's `TT_EVENT_SHELL_MARK`: the exit code, or
  // `TT_EXIT_CODE_UNKNOWN`.
  uint32_t exit_code;
  // `TT_EVENT_CHILD_EXITED`: 1 when a signal ended the child.
  uint8_t signaled;
  // Names this event to `tt_term_event_text`; never 0.
  uint64_t serial;
  // `TT_EVENT_LINK`: the link id, as `tt_link_span.id` holds it; 0 for every other kind.
  uint64_t id;
  // Per kind: a `TT_TITLE_*` or a `TT_SHELL_*`; 0 otherwise.
  uint32_t detail;
  // Bytes of the text (`TT_EVENT_TEXT_BODY`); 0 when there is none.
  uint32_t text_len;
  // Bytes of the second text (`TT_EVENT_TEXT_TITLE`); 0 when there is none.
  uint32_t title_len;
} tt_event;

// The cursor in viewport rows.
typedef struct tt_cursor {
  uint16_t row;
  uint16_t col;
  // 0 when hidden or scrolled out of the viewport.
  uint8_t visible;
} tt_cursor;

// One cell: 8 bytes, row after row, `cols * rows` of them.
typedef struct tt_cell {
  // The first code point of the cell's text; 0 when it shows none.
  uint32_t codepoint;
  // Index into `tt_frame_view.styles`.
  uint16_t style;
  // Columns covered: 1, 2 for a wide character, 0 for the cell behind it.
  uint8_t width;
  // `TT_CELL_*` bits.
  uint8_t flags;
} tt_cell;

// Neighbouring cells of one style and width with their text, the unit the platform shaper takes.
// Blank cells belong to no run.
typedef struct tt_run {
  // First column.
  uint16_t col;
  // Columns covered, wide spacers included.
  uint16_t cols;
  // Index into `tt_frame_view.styles`.
  uint16_t style;
  // Columns per character: 1 or 2.
  uint8_t width;
  // Byte offset of the run's text in its row's `text`.
  uint32_t text_start;
  // Byte length of the run's text.
  uint32_t text_len;
} tt_run;

// Columns `col..col + cols` of a row lead to link `id`; `tt_term_link_uri` reads where.
typedef struct tt_link_span {
  uint32_t id;
  uint16_t col;
  uint16_t cols;
} tt_link_span;

// One viewport row: the line it shows and its text.
typedef struct tt_row {
  // The line shown; stays with the line as it scrolls, so it keys a cache of shaped rows.
  uint64_t id;
  // Bumped by every change to the line.
  uint32_t generation;
  // The row's text runs, left to right.
  const struct tt_run *runs;
  size_t runs_len;
  // UTF-8 text of all runs, not NUL-terminated.
  const uint8_t *text;
  size_t text_len;
  // The row's link spans, left to right.
  const struct tt_link_span *links;
  size_t links_len;
} tt_row;

// A style as SGR set it; palette and reverse video are not resolved yet.
typedef struct tt_style {
  uint32_t fg;
  uint32_t bg;
  // `TT_COLOR_DEFAULT` follows the foreground.
  uint32_t underline_color;
  // `TT_ATTR_*` bits.
  uint16_t attrs;
  // A `TT_UNDERLINE_*` value.
  uint8_t underline;
} tt_style;

// Rows `start..end` are, unchanged, the rows from `from` of the previous picture: blit them before
// repainting the dirty rows.
typedef struct tt_scroll {
  // First row moved into.
  uint16_t start;
  // One past the last row moved into.
  uint16_t end;
  // Where `start` was in the previous picture.
  uint16_t from;
} tt_scroll;

// An image's pixels, read with `tt_image_pixels`.
typedef struct tt_image tt_image;

// One image shown in the viewport: the source rectangle of `image` scaled to `cols` x `rows` cells
// whose top-left cell is (`row`, `col`), moved by the pixel offset. A negative `z` draws below the
// text.
typedef struct tt_placement {
  // The pixels; valid until the next update or free of the frame.
  const struct tt_image *image;
  // Changes whenever the pixels behind `image` may have; a texture cache keys on `image` and
  // `generation`.
  uint64_t generation;
  // Viewport row of the top-left cell; negative above the viewport.
  int32_t row;
  // Column of the top-left cell.
  uint32_t col;
  // Width in cells.
  uint32_t cols;
  // Height in cells.
  uint32_t rows;
  // Pixel offset inside the top-left cell.
  uint32_t offset_x;
  uint32_t offset_y;
  // Source rectangle in image pixels.
  uint32_t src_x;
  uint32_t src_y;
  uint32_t src_w;
  uint32_t src_h;
  // Stacking order; equal values keep the order the images were placed in.
  int32_t z;
} tt_placement;

// What `tt_frame_update` hands back. Set `struct_size` before the call. Every pointer stays valid
// until the next update or free of the same frame.
typedef struct tt_frame_view {
  // `sizeof(tt_frame_view)` as the host knows it; on return, the bytes the core filled in.
  uint32_t struct_size;
  uint16_t cols;
  uint16_t rows;
  // 0 while synchronized output (mode 2026) holds the picture: nothing changed and there is no
  // damage; keep showing the last frame.
  uint8_t updated;
  // 1 when every row must be repainted (first frame, resize, other screen); every row is then also
  // listed in `dirty`.
  uint8_t full;
  struct tt_cursor cursor;
  // `cols * rows` cells, row-major.
  const struct tt_cell *cells;
  size_t cells_len;
  // One entry per viewport row.
  const struct tt_row *lines;
  size_t lines_len;
  // The style table that cells and runs index; entry 0 is the default.
  const struct tt_style *styles;
  size_t styles_len;
  // Rows to blit from the previous picture, applied before `dirty`.
  const struct tt_scroll *scrolls;
  size_t scrolls_len;
  // Rows to repaint, top to bottom.
  const uint16_t *dirty;
  size_t dirty_len;
  // The images in the viewport, lowest `z` first. The rows they cover are in `dirty` whenever a
  // placement over them changed.
  const struct tt_placement *placements;
  size_t placements_len;
} tt_frame_view;

// A search match: its first and last cell, both included. Lines are absolute, counted from the first
// line the terminal ever showed, so a match keeps its numbers while the text scrolls.
typedef struct tt_match {
  // `sizeof(tt_match)` as the host knows it.
  uint32_t struct_size;
  // Column of the first cell.
  uint16_t start_col;
  // Column of the last cell; a wide character's right half included.
  uint16_t end_col;
  // Line of the first cell.
  uint64_t start_line;
  // Line of the last cell.
  uint64_t end_line;
} tt_match;

// One key event, as Ghostty's embedding API reports it: what was pressed and what text it typed; the
// core picks the bytes. Fields a host does not know read as zero; the core needs up to `composing`.
typedef struct tt_key_event {
  // `sizeof(tt_key_event)` as the host knows it.
  uint32_t struct_size;
  // For a key of the main block, the code point it types on the US layout with no modifiers (`'a'`,
  // `'1'`, `' '`); for any other key a `TT_KEY_*` code.
  uint32_t key;
  // What the key types on the current layout with no modifiers (`с` for the `c` key on a Russian
  // layout); 0 when it is `key` itself.
  uint32_t unshifted;
  // `TT_KEY_PRESS`, `TT_KEY_REPEAT` or `TT_KEY_RELEASE`.
  uint8_t action;
  // `TT_MOD_*` bits held, including this key if it is a modifier.
  uint8_t mods;
  // `TT_MOD_*` bits the layout used up to type `text` (Option typing `å`).
  uint8_t consumed_mods;
  // 1 while an input method is composing: nothing is sent.
  uint8_t composing;
  // UTF-8 the key typed, with Shift and the consumed modifiers applied but not Control; may be empty.
  struct tt_str text;
} tt_key_event;

// One mouse event, in cells and in pixels: the core picks which the program gets. Fields a host does
// not know read as zero; the core needs up to `row`.
typedef struct tt_mouse_event {
  // `sizeof(tt_mouse_event)` as the host knows it.
  uint32_t struct_size;
  // `TT_MOUSE_PRESS`, `TT_MOUSE_RELEASE` or `TT_MOUSE_MOTION`.
  uint8_t action;
  // The button pressed or released; for motion, the lowest one held or `TT_MOUSE_NONE`.
  uint8_t button;
  // `TT_MOD_*` bits; Shift, Alt and Control are reported.
  uint8_t mods;
  // Viewport column, from 0.
  uint32_t col;
  // Viewport row, from 0.
  uint32_t row;
  // Pixels from the left of the text area.
  uint32_t x_px;
  // Pixels from the top of the text area.
  uint32_t y_px;
} tt_mouse_event;

// How to create a terminal. Fields a host does not know read as zero; `tt_term_new` reads only up to
// `scrollback`.
typedef struct tt_term_options {
  // `sizeof(tt_term_options)` as the host knows it.
  uint32_t struct_size;
  // `TT_ABI_VERSION` as the host was built with it.
  uint32_t abi_version;
  // Columns, at least 1.
  uint16_t cols;
  // Rows, at least 1.
  uint16_t rows;
  // History rows to keep; the core clamps it.
  uint32_t scrollback;
  // The program, searched on `PATH`; empty runs the user's shell.
  struct tt_str program;
  // `args_len` arguments; ignored for the user's shell.
  const struct tt_str *args;
  size_t args_len;
  // Working directory; empty keeps the host's.
  struct tt_str cwd;
  // `env_len` `NAME=value` entries added to the host's environment.
  const struct tt_str *env;
  size_t env_len;
  // Called from a core thread when there is something new to draw or poll; `NULL` for none. See
  // `tt_wakeup_fn`.
  tt_wakeup_fn wakeup;
  // Passed to `wakeup` as is.
  void *userdata;
} tt_term_options;

// cinterop reads this header for the types only: the functions below are the Kotlin exports
// themselves.
#ifndef TT_TYPES_ONLY

#ifdef __cplusplus
extern "C" {
#endif

// The ABI version of this library, `major << 16 | minor`. Call it before anything else.
uint32_t tt_abi_version(void);

// Creates a terminal with no child process: bytes reach it only through `tt_term_feed`. On `TT_OK`
// `*out` holds the handle; free it with `tt_term_free`.
tt_status tt_term_new(const struct tt_term_options *options, struct tt_term **out);

// Creates a terminal running a child on a new PTY: `options.program` with its arguments, or the
// user's shell. Output is parsed on core threads; `options.wakeup` says when to look. On `TT_OK`
// `*out` holds the handle; free it with `tt_term_free`, which ends the child. The strings and arrays
// in `*options` are read during this call only.
tt_status tt_term_spawn(const struct tt_term_options *options, struct tt_term **out);

// Parses `len` bytes of program output into the terminal, as if the child had written them. `bytes`
// may be `NULL` when `len` is 0.
tt_status tt_term_feed(const struct tt_term *term, const uint8_t *bytes, size_t len);

// Queues `len` bytes of input (keys, paste) for the child without blocking. `*written` gets how many
// leading bytes were taken: fewer than `len` when the child is not reading; offer the rest later.
// `TT_CLOSED` when there is no child or it has gone.
tt_status tt_term_write(const struct tt_term *term, const uint8_t *bytes, size_t len, size_t *written);

// Starts an interactive resize: the child's output is held back, so the host keeps drawing the last
// frame, until `tt_term_resize` with the final size. Without it every intermediate size would
// rewrap the history.
tt_status tt_term_resize_begin(const struct tt_term *term);

// Resizes the terminal to `cols` x `rows` cells: the primary screen and its history are rewrapped,
// the alternate screen is cut or padded. The child, if any, is told the new size (`width_px` and
// `height_px` are the view's size in pixels, 0 if unknown; divided by the cell counts they also give
// the cell size images are measured with), and output paused by `tt_term_resize_begin` flows again,
// whatever the outcome.
tt_status tt_term_resize(const struct tt_term *term, uint16_t cols, uint16_t rows, uint16_t width_px,
                         uint16_t height_px);

// Takes the next event into `*event`: `TT_OK` with one, `TT_EMPTY` when there is none. Call it until
// `TT_EMPTY` after every wakeup. The event's text stays readable through `tt_term_event_text` until
// the next poll.
tt_status tt_term_poll_event(const struct tt_term *term, struct tt_event *event);

// Copies part `part` (`TT_EVENT_TEXT_*`) of the text of event `serial` into `buf`: `*len` becomes
// its length, and `TT_FULL` with nothing copied when that is more than `cap` (pass `cap` 0 to ask
// for the length). Only the event polled last is held: an older `serial` answers `TT_EMPTY`.
// `TT_INVALID` for an unknown part, a `NULL` `len`, or a `NULL` `buf` with a `cap`.
tt_status tt_term_event_text(const struct tt_term *term, uint64_t serial, uint32_t part, uint8_t *buf,
                             size_t cap, size_t *len);

// Answers a `TT_EVENT_CLIPBOARD_READ` with `len` bytes of `data` for the selections `targets`
// (`targets_len` bytes, as the event named them), written to the child at once and whole.
// `TT_FULL` when the child's input queue lacks room now: call again later. `TT_INVALID` for targets
// that are not OSC 52 selections, or data too large for the queue ever to hold (about 190 KiB); deny
// those instead. `TT_CLOSED` when there is no child.
tt_status tt_term_clipboard_reply(const struct tt_term *term, const uint8_t *targets, size_t targets_len,
                                  const uint8_t *data, size_t len);

// Refuses a `TT_EVENT_CLIPBOARD_READ`: the child gets an empty selection, so it does not wait for an
// answer. Statuses as for `tt_term_clipboard_reply`.
tt_status tt_term_clipboard_deny(const struct tt_term *term, const uint8_t *targets, size_t targets_len);

// Copies the URI of link `id` (from `TT_EVENT_LINK` or a `tt_link_span`) into `buf`, as
// `tt_term_event_text` copies text. `TT_EMPTY` when no cell holds the id any more and the core freed
// it.
tt_status tt_term_link_uri(const struct tt_term *term, uint32_t id, uint8_t *buf, size_t cap, size_t *len);

// Starts a selection of `kind` (`TT_SELECT_*`) at viewport cell `row`, `col` (clamped), replacing any
// other. It covers that cell, word or line until extended; clear it on a click that did not drag.
// Output that rewrites its rows, a resize and a screen switch drop it. The frame marks its cells
// `TT_CELL_SELECTED`. `TT_INVALID` for an unknown kind.
tt_status tt_term_select_start(const struct tt_term *term, uint32_t kind, uint16_t row, uint16_t col);

// Moves the selection's free end to viewport cell `row`, `col` (clamped). No selection is no change.
tt_status tt_term_select_extend(const struct tt_term *term, uint16_t row, uint16_t col);

// Drops the selection.
tt_status tt_term_select_clear(const struct tt_term *term);

// Copies the selected text into `buf` as `tt_term_event_text` copies text: soft-wrapped rows joined,
// hard line ends as `\n`, wide characters once, blank and concealed cells as spaces, trailing spaces
// trimmed per line, at most `TT_MAX_SELECTION_BYTES`. `TT_EMPTY` with `*len` 0 when nothing is
// selected.
tt_status tt_term_selection_text(const struct tt_term *term, uint8_t *buf, size_t cap, size_t *len);

// Searches the history and the screen for `pattern_len` bytes of `pattern`, literal UTF-8, with
// `flags` (`TT_SEARCH_IGNORE_CASE` or 0), replacing any earlier search; the frame marks the matches
// in the viewport `TT_CELL_MATCH`. An empty pattern ends the search. `TT_INVALID`, also ending the
// search, for text that is not UTF-8, is longer than `TT_MAX_SEARCH_PATTERN` bytes, or an unknown
// flag.
tt_status tt_term_search_set(const struct tt_term *term, const uint8_t *pattern, size_t pattern_len,
                             uint32_t flags);

// Steps to the next match (`forward` 1) or the previous one (0): from the current match, or else from
// the top (bottom) of the viewport, wrapping round at the ends. The viewport scrolls to show it and
// the frame marks it `TT_CELL_CURRENT_MATCH`. `TT_OK` with the match in `*found`, which may be `NULL`;
// `TT_EMPTY` when there is no search or no match.
tt_status tt_term_search_step(const struct tt_term *term, uint8_t forward, struct tt_match *found);

// Writes how many matches there are, up to `TT_MAX_SEARCH_MATCHES`, to `*count`; 0 without a search.
tt_status tt_term_search_count(const struct tt_term *term, size_t *count);

// Sends a key event to the child, whole. `TT_OK` also when the active modes send nothing for it (a
// release in legacy mode, a composing key); `TT_CLOSED` when there is no child; `TT_FULL` when the
// child is not reading and its input queue lacks room, in which case nothing was sent. `TT_INVALID`
// for an unknown key code or action, or text that is not UTF-8. Input that sends bytes brings the
// viewport back to the screen.
tt_status tt_term_key(const struct tt_term *term, const struct tt_key_event *event);

// Sends text an input method committed outside a key event (dictation, the character viewer).
// Control characters are dropped. Statuses as for `tt_term_key`.
tt_status tt_term_text(const struct tt_term *term, const uint8_t *bytes, size_t len);

// Sends pasted text: bracketed, with any bracket marker inside removed, while the program asks for
// it (mode 2004); otherwise line feeds become carriage returns. A paste is sent whole or not at all,
// so one larger than the child's input queue (256 KiB) answers `TT_FULL`. Statuses otherwise as for
// `tt_term_key`.
tt_status tt_term_paste(const struct tt_term *term, const uint8_t *bytes, size_t len);

// Tells the program the view gained (`focused` 1) or lost (0) focus, while it asks for focus reports
// (mode 1004). Statuses as for `tt_term_key`; the viewport stays where it is.
tt_status tt_term_focus(const struct tt_term *term, uint8_t focused);

// Sends a mouse event if the program takes it. `*taken`, unless `NULL`, becomes 1 when it does
// (whatever the status): always while it tracks the mouse, and for a wheel step on the alternate
// screen, which becomes a cursor key (mode 1007). At 0 the event is the host's: select, or scroll the
// history with `tt_term_scroll_display`. `TT_INVALID` for an unknown action or button, or no button
// for a press or release. Statuses otherwise as for `tt_term_key`; the viewport stays where it is.
tt_status tt_term_mouse(const struct tt_term *term, const struct tt_mouse_event *event, uint8_t *taken);

// Moves the viewport `delta` rows back into history (negative: towards the screen), clamped to what
// history holds.
tt_status tt_term_scroll_display(const struct tt_term *term, int32_t delta);

// Frees the terminal. Also valid on a poisoned terminal; `NULL` is a no-op. No other call may use
// `term` during or after this one.
void tt_term_free(struct tt_term *term);

// A new, empty frame; the first update fills it. `NULL` only if the core failed. Free it with
// `tt_frame_free`.
struct tt_frame *tt_frame_new(void);

// Brings `frame` up to date with `term`'s viewport and describes it in `*view`. Holds the terminal
// lock only while it copies the rows that changed.
tt_status tt_frame_update(struct tt_frame *frame, const struct tt_term *term, struct tt_frame_view *view);

// Frees the frame and every buffer its views pointed to. `NULL` is a no-op.
void tt_frame_free(struct tt_frame *frame);

// The pixels of `image`: `height` rows of `stride` bytes, each `width` RGBA pixels of 4 bytes, straight
// (not premultiplied) alpha. Valid while the view that listed `image` is. Each out pointer may be
// `NULL`; a `NULL` image answers `NULL` and zeroes them.
const uint8_t *tt_image_pixels(const struct tt_image *image, uint32_t *width, uint32_t *height,
                               size_t *stride);

#ifdef __cplusplus
}
#endif

#endif // TT_TYPES_ONLY

// The frame is read in place, so these layouts are the core's own.
#ifdef __cplusplus
static_assert(sizeof(tt_cell) == 8, "tt_cell layout");
static_assert(sizeof(tt_run) == 16, "tt_run layout");
static_assert(sizeof(tt_link_span) == 8, "tt_link_span layout");
#else
_Static_assert(sizeof(tt_cell) == 8, "tt_cell layout");
_Static_assert(sizeof(tt_run) == 16, "tt_run layout");
_Static_assert(sizeof(tt_link_span) == 8, "tt_link_span layout");
#endif

#endif // SCULL_H
