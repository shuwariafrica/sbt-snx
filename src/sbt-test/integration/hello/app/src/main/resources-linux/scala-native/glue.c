#include "answer.h"

// SNX_VENDORED arrives only via the vendored library's per-platform options, so a successful compile proves the
// vendored option channels reach the C compile.
#ifndef SNX_VENDORED
#error "SNX_VENDORED not defined - the vendored option did not reach the C compile"
#endif

int snx_glue(void) { return snx_answer(); }
