// Every status a host can get, called through the prototypes of scull.h: the Kotlin tests call the
// exports as Kotlin functions and would not notice a parameter whose C type differs.

const std = @import("std");
const host = @import("host.zig");
const c = host.c;
const check = host.check;
const items = host.items;

fn options() c.tt_term_options {
    return host.options(10, 3, 0);
}

fn creation() void {
    var term: ?*c.tt_term = null;
    var o = options();
    check(@src(), c.tt_term_new(null, &term) == c.TT_INVALID);
    check(@src(), c.tt_term_new(&o, null) == c.TT_INVALID);

    o.struct_size = 15;
    check(@src(), c.tt_term_new(&o, &term) == c.TT_INVALID);
    o = options();
    o.abi_version = c.TT_ABI_VERSION + 1;
    check(@src(), c.tt_term_new(&o, &term) == c.TT_ABI_MISMATCH);
    o = options();
    o.cols = 0;
    check(@src(), c.tt_term_new(&o, &term) == c.TT_INVALID);
    check(@src(), term == null);

    // A host built before anything after `scrollback` existed.
    o = options();
    o.struct_size = 16;
    check(@src(), c.tt_term_new(&o, &term) == c.TT_OK);
    check(@src(), term != null);
    c.tt_term_free(term);

    o = options();
    const bad = [_]u8{0xff};
    o.program = .{ .ptr = &bad, .len = bad.len };
    term = null;
    check(@src(), c.tt_term_spawn(&o, &term) == c.TT_INVALID);
    check(@src(), term == null);

    c.tt_term_free(null);
    c.tt_frame_free(null);
}

fn callsOnATerminal() void {
    const o = options();
    var term: ?*c.tt_term = null;
    check(@src(), c.tt_term_new(&o, &term) == c.TT_OK);

    check(@src(), c.tt_term_feed(null, null, 0) == c.TT_INVALID);
    check(@src(), c.tt_term_feed(term, null, 1) == c.TT_INVALID);
    check(@src(), c.tt_term_feed(term, null, 0) == c.TT_OK);

    var written: usize = 99;
    check(@src(), c.tt_term_write(term, "x", 1, &written) == c.TT_CLOSED);
    check(@src(), written == 0);

    check(@src(), c.tt_term_resize_begin(term) == c.TT_OK);
    check(@src(), c.tt_term_resize(term, 0, 3, 0, 0) == c.TT_INVALID);
    check(@src(), c.tt_term_resize(term, 12, 4, 120, 64) == c.TT_OK);

    var event: c.tt_event = .{ .struct_size = @sizeOf(c.tt_event) };
    check(@src(), c.tt_term_poll_event(term, &event) == c.TT_EMPTY);
    check(@src(), c.tt_term_poll_event(term, null) == c.TT_INVALID);

    host.feed(term, "\x1b]2;fourteen bytes\x07");
    event.struct_size = @sizeOf(c.tt_event) - 1;
    check(@src(), c.tt_term_poll_event(term, &event) == c.TT_INVALID);
    event.struct_size = @sizeOf(c.tt_event);
    check(@src(), c.tt_term_poll_event(term, &event) == c.TT_OK);
    check(@src(), event.kind == c.TT_EVENT_TITLE and event.detail == c.TT_TITLE_WINDOW and event.text_len == 14);

    var buf: [32]u8 = undefined;
    var len: usize = 0;
    check(@src(), c.tt_term_event_text(term, event.serial, c.TT_EVENT_TEXT_BODY, &buf, 4, &len) == c.TT_FULL);
    check(@src(), len == 14);
    check(@src(), c.tt_term_event_text(term, event.serial, c.TT_EVENT_TEXT_BODY, null, 0, &len) == c.TT_FULL);
    check(@src(), c.tt_term_event_text(term, event.serial, c.TT_EVENT_TEXT_BODY, null, 4, &len) == c.TT_INVALID);
    check(@src(), c.tt_term_event_text(term, event.serial, c.TT_EVENT_TEXT_BODY, &buf, buf.len, null) == c.TT_INVALID);
    check(@src(), c.tt_term_event_text(term, event.serial, 9, &buf, buf.len, &len) == c.TT_INVALID);
    check(@src(), c.tt_term_event_text(term, event.serial + 1, c.TT_EVENT_TEXT_BODY, &buf, buf.len, &len) == c.TT_EMPTY);
    check(@src(), c.tt_term_event_text(term, event.serial, c.TT_EVENT_TEXT_BODY, &buf, buf.len, &len) == c.TT_OK);
    check(@src(), std.mem.eql(u8, buf[0..len], "fourteen bytes"));

    c.tt_term_free(term);
}

fn frames() void {
    const o = options();
    var term: ?*c.tt_term = null;
    check(@src(), c.tt_term_new(&o, &term) == c.TT_OK);
    const frame = c.tt_frame_new();
    check(@src(), frame != null);

    var view: c.tt_frame_view = .{};
    check(@src(), c.tt_frame_update(null, term, &view) == c.TT_INVALID);
    check(@src(), c.tt_frame_update(frame, null, &view) == c.TT_INVALID);
    check(@src(), c.tt_frame_update(frame, term, null) == c.TT_INVALID);
    view.struct_size = @sizeOf(c.tt_frame_view) - 1;
    check(@src(), c.tt_frame_update(frame, term, &view) == c.TT_INVALID);

    host.feed(term, "\x1b[1;38;2;1;2;3mh\xc3\xa9\xe4\xb8\x96");
    view.struct_size = @sizeOf(c.tt_frame_view);
    check(@src(), c.tt_frame_update(frame, term, &view) == c.TT_OK);
    check(@src(), view.updated == 1 and view.full == 1);
    check(@src(), view.cols == 10 and view.rows == 3 and view.dirty_len == 3);
    check(@src(), view.cursor.row == 0 and view.cursor.col == 4 and view.cursor.visible == 1);

    const cells = items(c.tt_cell, view.cells, view.cells_len);
    const h = &cells[0];
    check(@src(), h.codepoint == 'h' and h.width == 1 and h.flags == 0);
    const style = items(c.tt_style, view.styles, view.styles_len)[h.style];
    check(@src(), style.fg == (c.TT_COLOR_RGB | 0x010203) and style.bg == c.TT_COLOR_DEFAULT);
    check(@src(), style.attrs == c.TT_ATTR_BOLD and style.underline == c.TT_UNDERLINE_NONE);
    check(@src(), cells[2].codepoint == 0x4e16 and cells[2].width == 2);
    check(@src(), cells[3].width == 0);

    // A cell is one little-endian word, as hosts that read it whole expect.
    const word = std.mem.readInt(u64, std.mem.asBytes(h), .little);
    check(@src(), @as(u32, @truncate(word)) == 'h' and @as(u16, @truncate(word >> 32)) == h.style and @as(u8, @truncate(word >> 48)) == 1);

    const row = items(c.tt_row, view.lines, view.lines_len)[0];
    check(@src(), std.mem.eql(u8, items(u8, row.text, row.text_len), "h\xc3\xa9\xe4\xb8\x96"));
    const runs = items(c.tt_run, row.runs, row.runs_len);
    check(@src(), runs.len == 2);
    check(@src(), runs[0].col == 0 and runs[0].cols == 2 and runs[0].text_start == 0 and runs[0].text_len == 3);
    check(@src(), runs[1].col == 2 and runs[1].cols == 2 and runs[1].width == 2);
    check(@src(), runs[1].text_start == 3 and runs[1].text_len == 3);

    host.feed(term, "\x1b[?2026hmore");
    check(@src(), c.tt_frame_update(frame, term, &view) == c.TT_OK);
    check(@src(), view.updated == 0 and view.dirty_len == 0);
    host.feed(term, "\x1b[?2026l");
    check(@src(), c.tt_frame_update(frame, term, &view) == c.TT_OK);
    check(@src(), view.updated == 1 and view.full == 0 and view.dirty_len == 1 and items(u16, view.dirty, view.dirty_len)[0] == 0);

    c.tt_frame_free(frame);
    c.tt_term_free(term);
}

pub fn main() void {
    check(@src(), c.tt_abi_version() == c.TT_ABI_VERSION);
    creation();
    callsOnATerminal();
    frames();
    std.debug.print("status: ok\n", .{});
}
