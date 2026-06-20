#include "answer.h"

// This define arrives only via the test-scoped vendored library's link closure (Flags.defines -> -D), so a successful
// compile of this test-only glue proves the closure define reaches the test C compile.
#ifndef SNX_VENDORED
#error "SNX_VENDORED not defined - the vendored library's closure define did not reach the test C compile"
#endif

int snx_glue(void) { return snx_answer(); }
