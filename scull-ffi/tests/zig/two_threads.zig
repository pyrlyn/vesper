// A host's life cycle from two threads, run under ASan and under TSan: one thread feeds output
// while the UI thread updates frames and polls events; then a spawned child's output and exit
// arrive through the wakeup; then a running child is freed.
//
// Threads, locks and the condition variable are pthread's: std.Thread maps its own stacks, which
// the ASan runtime cannot unmap at thread exit, and a host is likelier to use pthread anyway.

const std = @import("std");
const host = @import("host.zig");
const c = host.c;
const check = host.check;
const items = host.items;

const LINES = 2000;
const EVERY = 100;

var feeding_done = std.atomic.Value(bool).init(false);

fn feeder(arg: ?*anyopaque) callconv(.c) ?*anyopaque {
    const term: ?*c.tt_term = @ptrCast(arg);
    var buf: [128]u8 = undefined;
    for (0..LINES) |i| {
        const line = std.fmt.bufPrint(&buf, "\x1b[3{d}mline {d} h\xc3\xa9llo \xe4\xb8\x96\x1b[0m\r\n", .{ i % 8, i }) catch host.fail(@src());
        host.feed(term, line);
        if (i % EVERY == 0) host.feed(term, "\x1b]2;title\x07\x07");
    }
    feeding_done.store(true, .release);
    return null;
}

fn update(frame: ?*c.tt_frame, term: ?*c.tt_term) c.tt_frame_view {
    var view: c.tt_frame_view = .{ .struct_size = @sizeOf(c.tt_frame_view) };
    check(@src(), c.tt_frame_update(frame, term, &view) == c.TT_OK);
    check(@src(), view.struct_size == @sizeOf(c.tt_frame_view));
    check(@src(), view.cells_len == @as(usize, view.cols) * view.rows);
    check(@src(), view.lines_len == view.rows);
    // Touch everything the view points at, so the sanitizers see every byte the core handed over.
    for (items(c.tt_cell, view.cells, view.cells_len)) |cell| check(@src(), cell.style < view.styles_len);
    for (items(c.tt_row, view.lines, view.lines_len)) |row| {
        for (items(c.tt_run, row.runs, row.runs_len)) |run| {
            check(@src(), @as(usize, run.text_start) + run.text_len <= row.text_len);
            check(@src(), @as(u32, run.col) + run.cols <= view.cols);
        }
    }
    for (items(u16, view.dirty, view.dirty_len)) |r| check(@src(), r < view.rows);
    for (items(c.tt_scroll, view.scrolls, view.scrolls_len)) |s| check(@src(), s.end <= view.rows);
    return view;
}

const Counts = struct { bells: u32 = 0, titles: u32 = 0 };

/// Counts events by kind; returns the last exit code seen, or null.
fn drain(term: ?*c.tt_term, counts: *Counts) ?u32 {
    var exit_code: ?u32 = null;
    while (true) {
        var event: c.tt_event = .{ .struct_size = @sizeOf(c.tt_event) };
        const status = c.tt_term_poll_event(term, &event);
        if (status == c.TT_EMPTY) return exit_code;
        check(@src(), status == c.TT_OK);
        check(@src(), event.serial != 0);
        if (event.kind == c.TT_EVENT_BELL) counts.bells += 1;
        if (event.kind == c.TT_EVENT_TITLE) {
            var buf: [16]u8 = undefined;
            var len: usize = 0;
            check(@src(), c.tt_term_event_text(term, event.serial, c.TT_EVENT_TEXT_BODY, &buf, buf.len, &len) == c.TT_OK);
            check(@src(), std.mem.eql(u8, buf[0..len], "title"));
            counts.titles += 1;
        }
        if (event.kind == c.TT_EVENT_CHILD_EXITED) exit_code = event.exit_code;
    }
}

fn fedFromAnotherThread() void {
    const o = host.options(80, 24, 1000);
    var term: ?*c.tt_term = null;
    check(@src(), c.tt_term_new(&o, &term) == c.TT_OK);
    const frame = c.tt_frame_new();
    check(@src(), frame != null);

    var thread: std.c.pthread_t = undefined;
    check(@src(), std.c.pthread_create(&thread, null, feeder, term) == .SUCCESS);
    var counts: Counts = .{};
    var frames: u32 = 0;
    while (!feeding_done.load(.acquire)) {
        _ = update(frame, term);
        _ = drain(term, &counts);
        frames += 1;
    }
    check(@src(), std.c.pthread_join(thread, null) == .SUCCESS);

    const view = update(frame, term);
    _ = drain(term, &counts);
    // Bells the UI has not polled yet coalesce into one, so a feeder that outruns it rings fewer.
    check(@src(), counts.bells >= 1 and counts.bells <= LINES / EVERY);
    check(@src(), counts.titles == LINES / EVERY);
    check(@src(), host.rowStarts(&view, "line 1999 h\xc3\xa9llo \xe4\xb8\x96"));
    std.debug.print("fed {d} lines over {d} frames\n", .{ LINES, frames });
    c.tt_frame_free(frame);
    c.tt_term_free(term);
}

var woken_lock: std.c.pthread_mutex_t = .{};
var woken_cond: std.c.pthread_cond_t = .{};
var woken = false;

fn onWakeup(userdata: ?*anyopaque) callconv(.c) void {
    check(@src(), userdata == @as(?*anyopaque, @ptrCast(&woken)));
    _ = std.c.pthread_mutex_lock(&woken_lock);
    woken = true;
    _ = std.c.pthread_cond_signal(&woken_cond);
    _ = std.c.pthread_mutex_unlock(&woken_lock);
}

/// Waits for a wakeup, at most a second, as a UI loop would before redrawing anyway.
fn waitForWakeup() void {
    var until: std.c.timespec = undefined;
    _ = std.c.clock_gettime(.REALTIME, &until);
    until.sec += 1;
    _ = std.c.pthread_mutex_lock(&woken_lock);
    while (!woken and std.c.pthread_cond_timedwait(&woken_cond, &woken_lock, &until) == .SUCCESS) {}
    woken = false;
    _ = std.c.pthread_mutex_unlock(&woken_lock);
}

fn childThroughTheWakeup() void {
    const args = [_]c.tt_str{ host.text("-c"), host.text("printf 'from child\\n'; exit 7") };
    var o = host.options(40, 10, 1000);
    o.program = host.text("/bin/sh");
    o.args = &args;
    o.args_len = args.len;
    o.wakeup = onWakeup;
    o.userdata = &woken;
    var term: ?*c.tt_term = null;
    check(@src(), c.tt_term_spawn(&o, &term) == c.TT_OK);
    const frame = c.tt_frame_new();

    var counts: Counts = .{};
    var exit_code: ?u32 = null;
    var tries: u32 = 0;
    while (exit_code == null and tries < 20) : (tries += 1) {
        waitForWakeup();
        _ = update(frame, term);
        exit_code = drain(term, &counts);
    }
    check(@src(), exit_code == 7);
    const view = update(frame, term);
    check(@src(), host.rowStarts(&view, "from child"));
    // The child is gone: input has nowhere to go.
    var written: usize = 1;
    const status = c.tt_term_write(term, "x", 1, &written);
    check(@src(), status == c.TT_CLOSED or (status == c.TT_OK and written <= 1));
    c.tt_frame_free(frame);
    c.tt_term_free(term);
}

fn freeWhileRunning() void {
    const args = [_]c.tt_str{host.text("30")};
    var o = host.options(40, 10, 1000);
    o.program = host.text("sleep");
    o.args = &args;
    o.args_len = args.len;
    var term: ?*c.tt_term = null;
    check(@src(), c.tt_term_spawn(&o, &term) == c.TT_OK);
    check(@src(), c.tt_term_resize_begin(term) == c.TT_OK);
    check(@src(), c.tt_term_resize(term, 60, 20, 600, 400) == c.TT_OK);
    var written: usize = 0;
    check(@src(), c.tt_term_write(term, "ignored\n", 8, &written) == c.TT_OK);
    check(@src(), written == 8);
    c.tt_term_free(term);
}

pub fn main() void {
    check(@src(), c.tt_abi_version() == c.TT_ABI_VERSION);
    fedFromAnotherThread();
    childThroughTheWakeup();
    freeWhileRunning();
    std.debug.print("two_threads: ok\n", .{});
}
