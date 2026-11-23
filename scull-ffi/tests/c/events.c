// The shell-integration events, link URIs, link spans, clipboard answers, selection and search,
// called through the prototypes of scull.h, with the sanitizers watching every buffer the host hands
// over.

#define _POSIX_C_SOURCE 200809L

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "scull.h"

#define CHECK(expr)                                                   \
  do {                                                                \
    if (!(expr)) {                                                    \
      fprintf(stderr, "%s:%d: failed: %s\n", __FILE__, __LINE__, #expr); \
      exit(1);                                                        \
    }                                                                 \
  } while (0)

static tt_term_options options(uint16_t cols, uint16_t rows) {
  tt_term_options o;
  memset(&o, 0, sizeof o);
  o.struct_size = sizeof o;
  o.abi_version = TT_ABI_VERSION;
  o.cols = cols;
  o.rows = rows;
  o.scrollback = 10;
  return o;
}

static tt_str text(const char *s) {
  tt_str t = {(const uint8_t *)s, strlen(s)};
  return t;
}

static void feed(tt_term *term, const char *s) {
  CHECK(tt_term_feed(term, (const uint8_t *)s, strlen(s)) == TT_OK);
}

static tt_event poll_one(tt_term *term) {
  tt_event e;
  memset(&e, 0, sizeof e);
  e.struct_size = sizeof e;
  CHECK(tt_term_poll_event(term, &e) == TT_OK);
  return e;
}

// Whether part [part] of event [e] is exactly [want], read into a buffer sized to the reported length.
static int part_is(tt_term *term, const tt_event *e, uint32_t part, const char *want) {
  size_t len = 0;
  tt_status status = tt_term_event_text(term, e->serial, part, NULL, 0, &len);
  if (len == 0) return status == TT_OK && want[0] == '\0';
  if (status != TT_FULL) return 0;
  uint8_t *buf = malloc(len);
  CHECK(buf != NULL);
  int same = tt_term_event_text(term, e->serial, part, buf, len, &len) == TT_OK && len == strlen(want) &&
             memcmp(buf, want, len) == 0;
  free(buf);
  return same;
}

static void every_kind(void) {
  tt_term_options o = options(10, 3);
  tt_term *term = NULL;
  CHECK(tt_term_new(&o, &term) == TT_OK);
  feed(term, "\x1b]7;file://h/tmp\a\x1b]133;A\a\x1b]133;D;5\a\x1b]133;D\a");
  feed(term, "\x1b]52;c;aGk=\a\x1b]52;p;?\a\x1b]777;notify;T;B\a");

  tt_event e = poll_one(term);
  CHECK(e.kind == TT_EVENT_DIRECTORY && part_is(term, &e, TT_EVENT_TEXT_BODY, "file://h/tmp"));
  e = poll_one(term);
  CHECK(e.kind == TT_EVENT_SHELL_MARK && e.detail == TT_SHELL_PROMPT_START && e.exit_code == 0);
  e = poll_one(term);
  CHECK(e.detail == TT_SHELL_COMMAND_FINISHED && e.exit_code == 5);
  e = poll_one(term);
  CHECK(e.detail == TT_SHELL_COMMAND_FINISHED && e.exit_code == TT_EXIT_CODE_UNKNOWN);
  e = poll_one(term);
  CHECK(e.kind == TT_EVENT_CLIPBOARD_WRITE && e.text_len == 2 && e.title_len == 1);
  CHECK(part_is(term, &e, TT_EVENT_TEXT_BODY, "hi") && part_is(term, &e, TT_EVENT_TEXT_TITLE, "c"));
  e = poll_one(term);
  CHECK(e.kind == TT_EVENT_CLIPBOARD_READ && e.text_len == 0 && part_is(term, &e, TT_EVENT_TEXT_TITLE, "p"));
  e = poll_one(term);
  CHECK(e.kind == TT_EVENT_NOTIFICATION && part_is(term, &e, TT_EVENT_TEXT_BODY, "B") &&
        part_is(term, &e, TT_EVENT_TEXT_TITLE, "T"));
  size_t len = 0;
  CHECK(tt_term_event_text(term, e.serial, TT_EVENT_TEXT_TITLE + 1, NULL, 0, &len) == TT_INVALID);
  tt_event none;
  memset(&none, 0, sizeof none);
  none.struct_size = sizeof none;
  CHECK(tt_term_poll_event(term, &none) == TT_EMPTY);
  tt_term_free(term);
}

static void links(void) {
  tt_term_options o = options(10, 3);
  tt_term *term = NULL;
  CHECK(tt_term_new(&o, &term) == TT_OK);
  feed(term, "x\x1b]8;;http://a\aab\x1b]8;;\ay");
  tt_event e = poll_one(term);
  CHECK(e.kind == TT_EVENT_LINK && e.id > 0);
  uint32_t id = (uint32_t)e.id;

  char buf[16];
  size_t len = 0;
  CHECK(tt_term_link_uri(term, id, (uint8_t *)buf, 3, &len) == TT_FULL && len == 8);
  CHECK(tt_term_link_uri(term, id, (uint8_t *)buf, sizeof buf, &len) == TT_OK);
  CHECK(len == 8 && memcmp(buf, "http://a", 8) == 0);
  CHECK(tt_term_link_uri(term, 0, (uint8_t *)buf, sizeof buf, &len) == TT_EMPTY);
  CHECK(tt_term_link_uri(term, id + 1000, (uint8_t *)buf, sizeof buf, &len) == TT_EMPTY);
  CHECK(tt_term_link_uri(term, id, (uint8_t *)buf, sizeof buf, NULL) == TT_INVALID);

  tt_frame *frame = tt_frame_new();
  CHECK(frame != NULL);
  tt_frame_view view;
  memset(&view, 0, sizeof view);
  view.struct_size = sizeof view;
  CHECK(tt_frame_update(frame, term, &view) == TT_OK);
  CHECK(view.lines[0].links_len == 1);
  CHECK(view.lines[0].links[0].id == id && view.lines[0].links[0].col == 1 && view.lines[0].links[0].cols == 2);
  CHECK(view.lines[1].links_len == 0);
  tt_frame_free(frame);
  tt_term_free(term);
}

static void clipboard_without_a_child(void) {
  tt_term_options o = options(10, 3);
  tt_term *term = NULL;
  CHECK(tt_term_new(&o, &term) == TT_OK);
  const uint8_t hi[] = {'h', 'i'};
  CHECK(tt_term_clipboard_reply(term, (const uint8_t *)"c", 1, hi, 2) == TT_CLOSED);
  CHECK(tt_term_clipboard_deny(term, (const uint8_t *)"c", 1) == TT_CLOSED);
  CHECK(tt_term_clipboard_reply(term, (const uint8_t *)"!", 1, hi, 2) == TT_INVALID);
  const uint8_t bad[] = {0xff};
  CHECK(tt_term_clipboard_deny(term, bad, 1) == TT_INVALID);
  CHECK(tt_term_clipboard_reply(term, NULL, 1, hi, 2) == TT_INVALID);
  // Past the clipboard cap: refused before a byte of the buffer is read.
  CHECK(tt_term_clipboard_reply(term, (const uint8_t *)"c", 1, hi, (size_t)2 << 20) == TT_INVALID);
  tt_term_free(term);
}

static int row_starts(const tt_frame_view *view, const char *prefix) {
  size_t n = strlen(prefix);
  for (size_t r = 0; r < view->lines_len; r++) {
    const tt_row *row = &view->lines[r];
    if (row->text_len >= n && memcmp(row->text, prefix, n) == 0) return 1;
  }
  return 0;
}

static void sleep_ms(long ms) {
  struct timespec t = {0, ms * 1000000L};
  nanosleep(&t, NULL);
}

// The child reads its answer without writing first; ESC and BEL come back as E and B.
static void a_waiting_child_gets_the_answer(void) {
  tt_str args[] = {text("-c"),
                   text("stty -icanon -echo; printf '\\033]52;c;?\\007'; head -c 12 | tr '\\033\\007' EB")};
  tt_term_options o = options(20, 3);
  o.program = text("/bin/sh");
  o.args = args;
  o.args_len = 2;
  tt_term *term = NULL;
  CHECK(tt_term_spawn(&o, &term) == TT_OK);

  int asked = 0;
  for (int tries = 0; !asked && tries < 1000; tries++) {
    tt_event e;
    memset(&e, 0, sizeof e);
    e.struct_size = sizeof e;
    while (!asked && tt_term_poll_event(term, &e) == TT_OK) asked = e.kind == TT_EVENT_CLIPBOARD_READ;
    if (!asked) sleep_ms(10);
  }
  CHECK(asked);
  const uint8_t hi[] = {'h', 'i'};
  CHECK(tt_term_clipboard_reply(term, (const uint8_t *)"c", 1, hi, 2) == TT_OK);

  tt_frame *frame = tt_frame_new();
  int seen = 0;
  for (int tries = 0; !seen && tries < 1000; tries++) {
    tt_frame_view view;
    memset(&view, 0, sizeof view);
    view.struct_size = sizeof view;
    CHECK(tt_frame_update(frame, term, &view) == TT_OK);
    seen = row_starts(&view, "E]52;c;aGk=B");
    if (!seen) sleep_ms(10);
  }
  CHECK(seen);
  tt_frame_free(frame);
  tt_term_free(term);
}

static void select_and_search(void) {
  tt_term_options o = options(10, 3);
  tt_term *term = NULL;
  CHECK(tt_term_new(&o, &term) == TT_OK);
  feed(term, "one two\r\nthree");

  char buf[16];
  size_t len = 99;
  CHECK(tt_term_selection_text(term, (uint8_t *)buf, sizeof buf, &len) == TT_EMPTY && len == 0);
  CHECK(tt_term_select_start(term, TT_SELECT_WORD, 0, 5) == TT_OK);
  CHECK(tt_term_selection_text(term, (uint8_t *)buf, sizeof buf, &len) == TT_OK);
  CHECK(len == 3 && memcmp(buf, "two", 3) == 0);
  CHECK(tt_term_select_extend(term, 1, 1) == TT_OK);
  CHECK(tt_term_selection_text(term, (uint8_t *)buf, 2, &len) == TT_FULL && len == 9);  // "two\nthree": words stay whole
  CHECK(tt_term_select_start(term, 99, 0, 0) == TT_INVALID);

  tt_str pattern = text("O");
  size_t count = 0;
  CHECK(tt_term_search_set(term, pattern.ptr, pattern.len, 0) == TT_OK);
  CHECK(tt_term_search_count(term, &count) == TT_OK && count == 0);
  CHECK(tt_term_search_set(term, pattern.ptr, pattern.len, TT_SEARCH_IGNORE_CASE) == TT_OK);
  CHECK(tt_term_search_count(term, &count) == TT_OK && count == 2);
  tt_match m;
  memset(&m, 0, sizeof m);
  m.struct_size = sizeof m;
  CHECK(tt_term_search_step(term, 0, &m) == TT_OK);
  CHECK(m.start_line == 0 && m.start_col == 6 && m.end_col == 6);
  CHECK(tt_term_search_step(term, 1, NULL) == TT_OK);
  CHECK(tt_term_search_set(term, pattern.ptr, pattern.len, 2) == TT_INVALID);
  CHECK(tt_term_search_step(term, 1, &m) == TT_EMPTY);

  tt_frame *frame = tt_frame_new();
  CHECK(frame != NULL);
  tt_frame_view view;
  memset(&view, 0, sizeof view);
  view.struct_size = sizeof view;
  CHECK(tt_frame_update(frame, term, &view) == TT_OK);
  CHECK(view.cells[4].flags == TT_CELL_SELECTED && view.cells[3].flags == 0);
  tt_frame_free(frame);
  tt_term_free(term);
}

int main(void) {
  CHECK(tt_abi_version() == TT_ABI_VERSION);
  every_kind();
  links();
  clipboard_without_a_child();
  select_and_search();
  a_waiting_child_gets_the_answer();
  printf("events: ok\n");
  return 0;
}
