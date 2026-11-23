// What the host programs share. They reach the core only through `scull`, the module
// `zig translate-c` makes of scull.h, so a parameter whose C type drifts from the export breaks here.

const std = @import("std");
pub const c = @import("scull");

/// Ends the run on a failed check, naming the line, as a sanitizer report would end it.
pub fn check(src: std.builtin.SourceLocation, ok: bool) void {
    if (!ok) fail(src);
}

pub fn fail(src: std.builtin.SourceLocation) noreturn {
    std.debug.print("{s}:{d}: check failed in {s}\n", .{ src.file, src.line, src.fn_name });
    std.process.exit(1);
}

pub fn options(cols: u16, rows: u16, scrollback: u32) c.tt_term_options {
    return .{
        .struct_size = @sizeOf(c.tt_term_options),
        .abi_version = c.TT_ABI_VERSION,
        .cols = cols,
        .rows = rows,
        .scrollback = scrollback,
    };
}

pub fn text(s: []const u8) c.tt_str {
    return .{ .ptr = s.ptr, .len = s.len };
}

pub fn feed(term: ?*c.tt_term, s: []const u8) void {
    check(@src(), c.tt_term_feed(term, s.ptr, s.len) == c.TT_OK);
}

/// A view array as a slice of the length the core reported, so every index the programs take is
/// bounds-checked against it; an empty array may come with a null pointer.
pub fn items(comptime T: type, ptr: [*c]const T, len: usize) []const T {
    return if (len == 0) &.{} else ptr[0..len];
}

pub fn rowStarts(view: *const c.tt_frame_view, prefix: []const u8) bool {
    for (items(c.tt_row, view.lines, view.lines_len)) |row| {
        if (std.mem.startsWith(u8, items(u8, row.text, row.text_len), prefix)) return true;
    }
    return false;
}

pub fn sleepMs(ms: u32) void {
    const t: std.c.timespec = .{ .sec = 0, .nsec = @as(i64, ms) * std.time.ns_per_ms };
    _ = std.c.nanosleep(&t, null);
}
