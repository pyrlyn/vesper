// The shell-integration events, link URIs, link spans, clipboard answers, selection and search,
// called through the prototypes of scull.h.

const std = @import("std");
const host = @import("host.zig");
const c = host.c;
const check = host.check;
const items = host.items;

fn newTerm(cols: u16, rows: u16) ?*c.tt_term {
    const o = host.options(cols, rows, 10);
    var term: ?*c.tt_term = null;
    check(@src(), c.tt_term_new(&o, &term) == c.TT_OK);
    return term;
}

fn pollOne(term: ?*c.tt_term) c.tt_event {
    var e: c.tt_event = .{ .struct_size = @sizeOf(c.tt_event) };
    check(@src(), c.tt_term_poll_event(term, &e) == c.TT_OK);
    return e;
}

/// Whether part [part] of event [e] is exactly [want], read into a heap buffer sized to the
/// reported length.
fn partIs(term: ?*c.tt_term, e: c.tt_event, part: u32, want: []const u8) bool {
    var len: usize = 0;
    const status = c.tt_term_event_text(term, e.serial, part, null, 0, &len);
    if (len == 0) return status == c.TT_OK and want.len == 0;
    if (status != c.TT_FULL) return false;
    const buf = std.heap.c_allocator.alloc(u8, len) catch host.fail(@src());
    defer std.heap.c_allocator.free(buf);
    return c.tt_term_event_text(term, e.serial, part, buf.ptr, buf.len, &len) == c.TT_OK and
        std.mem.eql(u8, buf[0..len], want);
}

fn everyKind() void {
    const term = newTerm(10, 3);
    host.feed(term, "\x1b]7;file://h/tmp\x07\x1b]133;A\x07\x1b]133;D;5\x07\x1b]133;D\x07");
    host.feed(term, "\x1b]52;c;aGk=\x07\x1b]52;p;?\x07\x1b]777;notify;T;B\x07");

    var e = pollOne(term);
    check(@src(), e.kind == c.TT_EVENT_DIRECTORY and partIs(term, e, c.TT_EVENT_TEXT_BODY, "file://h/tmp"));
    e = pollOne(term);
    check(@src(), e.kind == c.TT_EVENT_SHELL_MARK and e.detail == c.TT_SHELL_PROMPT_START and e.exit_code == 0);
    e = pollOne(term);
    check(@src(), e.detail == c.TT_SHELL_COMMAND_FINISHED and e.exit_code == 5);
    e = pollOne(term);
    check(@src(), e.detail == c.TT_SHELL_COMMAND_FINISHED and e.exit_code == c.TT_EXIT_CODE_UNKNOWN);
    e = pollOne(term);
    check(@src(), e.kind == c.TT_EVENT_CLIPBOARD_WRITE and e.text_len == 2 and e.title_len == 1);
    check(@src(), partIs(term, e, c.TT_EVENT_TEXT_BODY, "hi") and partIs(term, e, c.TT_EVENT_TEXT_TITLE, "c"));
    e = pollOne(term);
    check(@src(), e.kind == c.TT_EVENT_CLIPBOARD_READ and e.text_len == 0 and partIs(term, e, c.TT_EVENT_TEXT_TITLE, "p"));
    e = pollOne(term);
    check(@src(), e.kind == c.TT_EVENT_NOTIFICATION and partIs(term, e, c.TT_EVENT_TEXT_BODY, "B") and
        partIs(term, e, c.TT_EVENT_TEXT_TITLE, "T"));
    var len: usize = 0;
    check(@src(), c.tt_term_event_text(term, e.serial, c.TT_EVENT_TEXT_TITLE + 1, null, 0, &len) == c.TT_INVALID);
    var none: c.tt_event = .{ .struct_size = @sizeOf(c.tt_event) };
    check(@src(), c.tt_term_poll_event(term, &none) == c.TT_EMPTY);
    c.tt_term_free(term);
}

fn links() void {
    const term = newTerm(10, 3);
    host.feed(term, "x\x1b]8;;http://a\x07ab\x1b]8;;\x07y");
    const e = pollOne(term);
    check(@src(), e.kind == c.TT_EVENT_LINK and e.id > 0);
    const id: u32 = @intCast(e.id);

    var buf: [16]u8 = undefined;
    var len: usize = 0;
    check(@src(), c.tt_term_link_uri(term, id, &buf, 3, &len) == c.TT_FULL and len == 8);
    check(@src(), c.tt_term_link_uri(term, id, &buf, buf.len, &len) == c.TT_OK);
    check(@src(), std.mem.eql(u8, buf[0..len], "http://a"));
    check(@src(), c.tt_term_link_uri(term, 0, &buf, buf.len, &len) == c.TT_EMPTY);
    check(@src(), c.tt_term_link_uri(term, id + 1000, &buf, buf.len, &len) == c.TT_EMPTY);
    check(@src(), c.tt_term_link_uri(term, id, &buf, buf.len, null) == c.TT_INVALID);

    const frame = c.tt_frame_new();
    check(@src(), frame != null);
    var view: c.tt_frame_view = .{ .struct_size = @sizeOf(c.tt_frame_view) };
    check(@src(), c.tt_frame_update(frame, term, &view) == c.TT_OK);
    const lines = items(c.tt_row, view.lines, view.lines_len);
    const spans = items(c.tt_link_span, lines[0].links, lines[0].links_len);
    check(@src(), spans.len == 1);
    check(@src(), spans[0].id == id and spans[0].col == 1 and spans[0].cols == 2);
    check(@src(), lines[1].links_len == 0);
    c.tt_frame_free(frame);
    c.tt_term_free(term);
}

fn clipboardWithoutAChild() void {
    const term = newTerm(10, 3);
    const hi = [_]u8{ 'h', 'i' };
    check(@src(), c.tt_term_clipboard_reply(term, "c", 1, &hi, hi.len) == c.TT_CLOSED);
    check(@src(), c.tt_term_clipboard_deny(term, "c", 1) == c.TT_CLOSED);
    check(@src(), c.tt_term_clipboard_reply(term, "!", 1, &hi, hi.len) == c.TT_INVALID);
    const bad = [_]u8{0xff};
    check(@src(), c.tt_term_clipboard_deny(term, &bad, bad.len) == c.TT_INVALID);
    check(@src(), c.tt_term_clipboard_reply(term, null, 1, &hi, hi.len) == c.TT_INVALID);
    // Past the clipboard cap: refused before a byte of the buffer is read.
    check(@src(), c.tt_term_clipboard_reply(term, "c", 1, &hi, 2 << 20) == c.TT_INVALID);
    c.tt_term_free(term);
}

// The child reads its answer without writing first; ESC and BEL come back as E and B.
fn aWaitingChildGetsTheAnswer() void {
    const args = [_]c.tt_str{
        host.text("-c"),
        host.text("stty -icanon -echo; printf '\\033]52;c;?\\007'; head -c 12 | tr '\\033\\007' EB"),
    };
    var o = host.options(20, 3, 10);
    o.program = host.text("/bin/sh");
    o.args = &args;
    o.args_len = args.len;
    var term: ?*c.tt_term = null;
    check(@src(), c.tt_term_spawn(&o, &term) == c.TT_OK);

    var asked = false;
    var tries: u32 = 0;
    while (!asked and tries < 1000) : (tries += 1) {
        var e: c.tt_event = .{ .struct_size = @sizeOf(c.tt_event) };
        while (!asked and c.tt_term_poll_event(term, &e) == c.TT_OK) asked = e.kind == c.TT_EVENT_CLIPBOARD_READ;
        if (!asked) host.sleepMs(10);
    }
    check(@src(), asked);
    const hi = [_]u8{ 'h', 'i' };
    check(@src(), c.tt_term_clipboard_reply(term, "c", 1, &hi, hi.len) == c.TT_OK);

    const frame = c.tt_frame_new();
    var seen = false;
    tries = 0;
    while (!seen and tries < 1000) : (tries += 1) {
        var view: c.tt_frame_view = .{ .struct_size = @sizeOf(c.tt_frame_view) };
        check(@src(), c.tt_frame_update(frame, term, &view) == c.TT_OK);
        seen = host.rowStarts(&view, "E]52;c;aGk=B");
        if (!seen) host.sleepMs(10);
    }
    check(@src(), seen);
    c.tt_frame_free(frame);
    c.tt_term_free(term);
}

fn selectAndSearch() void {
    const term = newTerm(10, 3);
    host.feed(term, "one two\r\nthree");

    var buf: [16]u8 = undefined;
    var len: usize = 99;
    check(@src(), c.tt_term_selection_text(term, &buf, buf.len, &len) == c.TT_EMPTY and len == 0);
    check(@src(), c.tt_term_select_start(term, c.TT_SELECT_WORD, 0, 5) == c.TT_OK);
    check(@src(), c.tt_term_selection_text(term, &buf, buf.len, &len) == c.TT_OK);
    check(@src(), std.mem.eql(u8, buf[0..len], "two"));
    check(@src(), c.tt_term_select_extend(term, 1, 1) == c.TT_OK);
    // "two\nthree": words stay whole.
    check(@src(), c.tt_term_selection_text(term, &buf, 2, &len) == c.TT_FULL and len == 9);
    check(@src(), c.tt_term_select_start(term, 99, 0, 0) == c.TT_INVALID);

    const pattern = "O";
    var count: usize = 0;
    check(@src(), c.tt_term_search_set(term, pattern, pattern.len, 0) == c.TT_OK);
    check(@src(), c.tt_term_search_count(term, &count) == c.TT_OK and count == 0);
    check(@src(), c.tt_term_search_set(term, pattern, pattern.len, c.TT_SEARCH_IGNORE_CASE) == c.TT_OK);
    check(@src(), c.tt_term_search_count(term, &count) == c.TT_OK and count == 2);
    var m: c.tt_match = .{ .struct_size = @sizeOf(c.tt_match) };
    check(@src(), c.tt_term_search_step(term, 0, &m) == c.TT_OK);
    check(@src(), m.start_line == 0 and m.start_col == 6 and m.end_col == 6);
    check(@src(), c.tt_term_search_step(term, 1, null) == c.TT_OK);
    check(@src(), c.tt_term_search_set(term, pattern, pattern.len, 2) == c.TT_INVALID);
    check(@src(), c.tt_term_search_step(term, 1, &m) == c.TT_EMPTY);

    const frame = c.tt_frame_new();
    check(@src(), frame != null);
    var view: c.tt_frame_view = .{ .struct_size = @sizeOf(c.tt_frame_view) };
    check(@src(), c.tt_frame_update(frame, term, &view) == c.TT_OK);
    const cells = items(c.tt_cell, view.cells, view.cells_len);
    check(@src(), cells[4].flags == c.TT_CELL_SELECTED and cells[3].flags == 0);
    c.tt_frame_free(frame);
    c.tt_term_free(term);
}

pub fn main() void {
    check(@src(), c.tt_abi_version() == c.TT_ABI_VERSION);
    everyKind();
    links();
    clipboardWithoutAChild();
    selectAndSearch();
    aWaitingChildGetsTheAnswer();
    std.debug.print("events: ok\n", .{});
}
