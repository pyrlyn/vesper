// A host's life cycle from two threads, run under ASan+UBSan and under TSan: one thread feeds
// output while the UI thread updates frames and polls events; then a spawned child's output and
// exit arrive through the wakeup; then a running child is freed.

// clock_gettime is POSIX, which -std=c11 hides on glibc.
#define _POSIX_C_SOURCE 200809L

#include <pthread.h>
#include <stdatomic.h>
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

#define LINES 2000
#define EVERY 100

static tt_term_options options(uint16_t cols, uint16_t rows) {
  tt_term_options o;
  memset(&o, 0, sizeof o);
  o.struct_size = sizeof o;
  o.abi_version = TT_ABI_VERSION;
  o.cols = cols;
  o.rows = rows;
  o.scrollback = 1000;
  return o;
}

static tt_str text(const char *s) {
  tt_str str = {(const uint8_t *)s, strlen(s)};
  return str;
}

static void feed(tt_term *term, const char *s) {
  CHECK(tt_term_feed(term, (const uint8_t *)s, strlen(s)) == TT_OK);
}

static atomic_int feeding_done;

static void *feeder(void *arg) {
  tt_term *term = arg;
  char line[128];
  for (int i = 0; i < LINES; i++) {
    snprintf(line, sizeof line, "\x1b[3%dmline %d h\xc3\xa9llo \xe4\xb8\x96\x1b[0m\r\n", i % 8, i);
    feed(term, line);
    if (i % EVERY == 0) feed(term, "\x1b]2;title\x07\x07");
  }
  atomic_store(&feeding_done, 1);
  return NULL;
}

static tt_frame_view update(tt_frame *frame, tt_term *term) {
  tt_frame_view view;
  memset(&view, 0, sizeof view);
  view.struct_size = sizeof view;
  CHECK(tt_frame_update(frame, term, &view) == TT_OK);
  CHECK(view.struct_size == sizeof view);
  CHECK(view.cells_len == (size_t)view.cols * view.rows);
  CHECK(view.lines_len == view.rows);
  // Touch everything the view points at, so the sanitizers see every byte the core handed over.
  for (size_t i = 0; i < view.cells_len; i++) CHECK(view.cells[i].style < view.styles_len);
  for (size_t r = 0; r < view.lines_len; r++) {
    const tt_row *row = &view.lines[r];
    for (size_t k = 0; k < row->runs_len; k++) {
      const tt_run *run = &row->runs[k];
      CHECK((size_t)run->text_start + run->text_len <= row->text_len);
      CHECK(run->col + run->cols <= view.cols);
    }
  }
  for (size_t i = 0; i < view.dirty_len; i++) CHECK(view.dirty[i] < view.rows);
  for (size_t i = 0; i < view.scrolls_len; i++) CHECK(view.scrolls[i].end <= view.rows);
  return view;
}

// Counts events by kind; returns the last exit code seen, or -1.
static long drain(tt_term *term, int *bells, int *titles) {
  long exit_code = -1;
  for (;;) {
    tt_event event;
    memset(&event, 0, sizeof event);
    event.struct_size = sizeof event;
    tt_status status = tt_term_poll_event(term, &event);
    if (status == TT_EMPTY) return exit_code;
    CHECK(status == TT_OK);
    CHECK(event.serial != 0);
    if (event.kind == TT_EVENT_BELL) ++*bells;
    if (event.kind == TT_EVENT_TITLE) {
      char buf[16];
      size_t len = 0;
      CHECK(tt_term_event_text(term, event.serial, TT_EVENT_TEXT_BODY, (uint8_t *)buf, sizeof buf, &len) == TT_OK);
      CHECK(len == 5 && memcmp(buf, "title", 5) == 0);
      ++*titles;
    }
    if (event.kind == TT_EVENT_CHILD_EXITED) exit_code = event.exit_code;
  }
}

static int row_starts(const tt_frame_view *view, const char *prefix) {
  size_t n = strlen(prefix);
  for (size_t r = 0; r < view->lines_len; r++) {
    const tt_row *row = &view->lines[r];
    if (row->text_len >= n && memcmp(row->text, prefix, n) == 0) return 1;
  }
  return 0;
}

static void fed_from_another_thread(void) {
  tt_term_options o = options(80, 24);
  tt_term *term = NULL;
  CHECK(tt_term_new(&o, &term) == TT_OK);
  tt_frame *frame = tt_frame_new();
  CHECK(frame != NULL);

  pthread_t thread;
  CHECK(pthread_create(&thread, NULL, feeder, term) == 0);
  int bells = 0, titles = 0, frames = 0;
  while (!atomic_load(&feeding_done)) {
    update(frame, term);
    drain(term, &bells, &titles);
    frames++;
  }
  CHECK(pthread_join(thread, NULL) == 0);

  tt_frame_view view = update(frame, term);
  drain(term, &bells, &titles);
  CHECK(bells == LINES / EVERY);
  CHECK(titles == LINES / EVERY);
  CHECK(row_starts(&view, "line 1999 h\xc3\xa9llo \xe4\xb8\x96"));
  printf("fed %d lines over %d frames\n", LINES, frames);
  tt_frame_free(frame);
  tt_term_free(term);
}

static pthread_mutex_t woken_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t woken_cond = PTHREAD_COND_INITIALIZER;
static int woken;

static void on_wakeup(void *userdata) {
  CHECK(userdata == &woken);
  pthread_mutex_lock(&woken_lock);
  woken = 1;
  pthread_cond_signal(&woken_cond);
  pthread_mutex_unlock(&woken_lock);
}

// Waits for a wakeup, at most a second, as a UI loop would before redrawing anyway.
static void wait_for_wakeup(void) {
  struct timespec until;
  clock_gettime(CLOCK_REALTIME, &until);
  until.tv_sec += 1;
  pthread_mutex_lock(&woken_lock);
  while (!woken && pthread_cond_timedwait(&woken_cond, &woken_lock, &until) == 0) {
  }
  woken = 0;
  pthread_mutex_unlock(&woken_lock);
}

static void child_through_the_wakeup(void) {
  tt_str args[] = {text("-c"), text("printf 'from child\\n'; exit 7")};
  tt_term_options o = options(40, 10);
  o.program = text("/bin/sh");
  o.args = args;
  o.args_len = 2;
  o.wakeup = on_wakeup;
  o.userdata = &woken;
  tt_term *term = NULL;
  CHECK(tt_term_spawn(&o, &term) == TT_OK);
  tt_frame *frame = tt_frame_new();

  int bells = 0, titles = 0;
  long exit_code = -1;
  for (int tries = 0; exit_code < 0 && tries < 20; tries++) {
    wait_for_wakeup();
    update(frame, term);
    exit_code = drain(term, &bells, &titles);
  }
  CHECK(exit_code == 7);
  tt_frame_view view = update(frame, term);
  CHECK(row_starts(&view, "from child"));
  // The child is gone: input has nowhere to go.
  size_t written = 1;
  tt_status status = tt_term_write(term, (const uint8_t *)"x", 1, &written);
  CHECK(status == TT_CLOSED || (status == TT_OK && written <= 1));
  tt_frame_free(frame);
  tt_term_free(term);
}

static void free_while_running(void) {
  tt_str args[] = {text("30")};
  tt_term_options o = options(40, 10);
  o.program = text("sleep");
  o.args = args;
  o.args_len = 1;
  tt_term *term = NULL;
  CHECK(tt_term_spawn(&o, &term) == TT_OK);
  CHECK(tt_term_resize_begin(term) == TT_OK);
  CHECK(tt_term_resize(term, 60, 20, 600, 400) == TT_OK);
  size_t written = 0;
  CHECK(tt_term_write(term, (const uint8_t *)"ignored\n", 8, &written) == TT_OK);
  CHECK(written == 8);
  tt_term_free(term);
}

int main(void) {
  CHECK(tt_abi_version() == TT_ABI_VERSION);
  fed_from_another_thread();
  child_through_the_wakeup();
  free_while_running();
  printf("two_threads: ok\n");
  return 0;
}
