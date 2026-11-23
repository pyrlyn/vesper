// Every status a host can get, called through the prototypes of scull.h: the Kotlin tests call the
// exports as Kotlin functions and would not notice a parameter whose C type differs.

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "scull.h"

#define CHECK(expr)                                                   \
  do {                                                                \
    if (!(expr)) {                                                    \
      fprintf(stderr, "%s:%d: failed: %s\n", __FILE__, __LINE__, #expr); \
      exit(1);                                                        \
    }                                                                 \
  } while (0)

static tt_term_options options(void) {
  tt_term_options o;
  memset(&o, 0, sizeof o);
  o.struct_size = sizeof o;
  o.abi_version = TT_ABI_VERSION;
  o.cols = 10;
  o.rows = 3;
  return o;
}

static void feed(tt_term *term, const char *s) {
  CHECK(tt_term_feed(term, (const uint8_t *)s, strlen(s)) == TT_OK);
}

static void creation(void) {
  tt_term *term = NULL;
  tt_term_options o = options();
  CHECK(tt_term_new(NULL, &term) == TT_INVALID);
  CHECK(tt_term_new(&o, NULL) == TT_INVALID);

  o.struct_size = 15;
  CHECK(tt_term_new(&o, &term) == TT_INVALID);
  o = options();
  o.abi_version = TT_ABI_VERSION + 1;
  CHECK(tt_term_new(&o, &term) == TT_ABI_MISMATCH);
  o = options();
  o.cols = 0;
  CHECK(tt_term_new(&o, &term) == TT_INVALID);
  CHECK(term == NULL);

  // A host built before anything after `scrollback` existed.
  o = options();
  o.struct_size = 16;
  CHECK(tt_term_new(&o, &term) == TT_OK);
  CHECK(term != NULL);
  tt_term_free(term);

  o = options();
  const uint8_t bad[] = {0xff};
  o.program.ptr = bad;
  o.program.len = 1;
  term = NULL;
  CHECK(tt_term_spawn(&o, &term) == TT_INVALID);
  CHECK(term == NULL);

  tt_term_free(NULL);
  tt_frame_free(NULL);
}

static void calls_on_a_terminal(void) {
  tt_term_options o = options();
  tt_term *term = NULL;
  CHECK(tt_term_new(&o, &term) == TT_OK);

  CHECK(tt_term_feed(NULL, NULL, 0) == TT_INVALID);
  CHECK(tt_term_feed(term, NULL, 1) == TT_INVALID);
  CHECK(tt_term_feed(term, NULL, 0) == TT_OK);

  size_t written = 99;
  CHECK(tt_term_write(term, (const uint8_t *)"x", 1, &written) == TT_CLOSED);
  CHECK(written == 0);

  CHECK(tt_term_resize_begin(term) == TT_OK);
  CHECK(tt_term_resize(term, 0, 3, 0, 0) == TT_INVALID);
  CHECK(tt_term_resize(term, 12, 4, 120, 64) == TT_OK);

  tt_event event;
  memset(&event, 0, sizeof event);
  event.struct_size = sizeof event;
  CHECK(tt_term_poll_event(term, &event) == TT_EMPTY);
  CHECK(tt_term_poll_event(term, NULL) == TT_INVALID);

  feed(term, "\x1b]2;fourteen bytes\x07");
  event.struct_size = sizeof event - 1;
  CHECK(tt_term_poll_event(term, &event) == TT_INVALID);
  event.struct_size = sizeof event;
  CHECK(tt_term_poll_event(term, &event) == TT_OK);
  CHECK(event.kind == TT_EVENT_TITLE && event.detail == TT_TITLE_WINDOW && event.text_len == 14);

  uint8_t buf[32];
  size_t len = 0;
  CHECK(tt_term_event_text(term, event.serial, TT_EVENT_TEXT_BODY, buf, 4, &len) == TT_FULL);
  CHECK(len == 14);
  CHECK(tt_term_event_text(term, event.serial, TT_EVENT_TEXT_BODY, NULL, 0, &len) == TT_FULL);
  CHECK(tt_term_event_text(term, event.serial, TT_EVENT_TEXT_BODY, NULL, 4, &len) == TT_INVALID);
  CHECK(tt_term_event_text(term, event.serial, TT_EVENT_TEXT_BODY, buf, sizeof buf, NULL) == TT_INVALID);
  CHECK(tt_term_event_text(term, event.serial, 9, buf, sizeof buf, &len) == TT_INVALID);
  CHECK(tt_term_event_text(term, event.serial + 1, TT_EVENT_TEXT_BODY, buf, sizeof buf, &len) == TT_EMPTY);
  CHECK(tt_term_event_text(term, event.serial, TT_EVENT_TEXT_BODY, buf, sizeof buf, &len) == TT_OK);
  CHECK(len == 14 && memcmp(buf, "fourteen bytes", 14) == 0);

  tt_term_free(term);
}

static void frames(void) {
  tt_term_options o = options();
  tt_term *term = NULL;
  CHECK(tt_term_new(&o, &term) == TT_OK);
  tt_frame *frame = tt_frame_new();
  CHECK(frame != NULL);

  tt_frame_view view;
  memset(&view, 0, sizeof view);
  CHECK(tt_frame_update(NULL, term, &view) == TT_INVALID);
  CHECK(tt_frame_update(frame, NULL, &view) == TT_INVALID);
  CHECK(tt_frame_update(frame, term, NULL) == TT_INVALID);
  view.struct_size = sizeof view - 1;
  CHECK(tt_frame_update(frame, term, &view) == TT_INVALID);

  feed(term, "\x1b[1;38;2;1;2;3mh\xc3\xa9\xe4\xb8\x96");
  view.struct_size = sizeof view;
  CHECK(tt_frame_update(frame, term, &view) == TT_OK);
  CHECK(view.updated == 1 && view.full == 1);
  CHECK(view.cols == 10 && view.rows == 3 && view.dirty_len == 3);
  CHECK(view.cursor.row == 0 && view.cursor.col == 4 && view.cursor.visible == 1);

  const tt_cell *h = &view.cells[0];
  CHECK(h->codepoint == 'h' && h->width == 1 && h->flags == 0);
  const tt_style *style = &view.styles[h->style];
  CHECK(style->fg == (TT_COLOR_RGB | 0x010203u) && style->bg == TT_COLOR_DEFAULT);
  CHECK(style->attrs == TT_ATTR_BOLD && style->underline == TT_UNDERLINE_NONE);
  CHECK(view.cells[2].codepoint == 0x4e16 && view.cells[2].width == 2);
  CHECK(view.cells[3].width == 0);

  // A cell is one little-endian word, as hosts that read it whole expect.
  uint64_t word;
  memcpy(&word, h, sizeof word);
  CHECK((uint32_t)word == 'h' && (uint16_t)(word >> 32) == h->style && (uint8_t)(word >> 48) == 1);

  const tt_row *row = &view.lines[0];
  CHECK(row->text_len == 6 && memcmp(row->text, "h\xc3\xa9\xe4\xb8\x96", 6) == 0);
  CHECK(row->runs_len == 2);
  CHECK(row->runs[0].col == 0 && row->runs[0].cols == 2 && row->runs[0].text_start == 0 && row->runs[0].text_len == 3);
  CHECK(row->runs[1].col == 2 && row->runs[1].cols == 2 && row->runs[1].width == 2);
  CHECK(row->runs[1].text_start == 3 && row->runs[1].text_len == 3);

  feed(term, "\x1b[?2026hmore");
  CHECK(tt_frame_update(frame, term, &view) == TT_OK);
  CHECK(view.updated == 0 && view.dirty_len == 0);
  feed(term, "\x1b[?2026l");
  CHECK(tt_frame_update(frame, term, &view) == TT_OK);
  CHECK(view.updated == 1 && view.full == 0 && view.dirty_len == 1 && view.dirty[0] == 0);

  tt_frame_free(frame);
  tt_term_free(term);
}

int main(void) {
  CHECK(tt_abi_version() == TT_ABI_VERSION);
  creation();
  calls_on_a_terminal();
  frames();
  printf("status: ok\n");
  return 0;
}
