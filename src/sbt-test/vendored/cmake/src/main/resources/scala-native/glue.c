#include "answer.h"

// This define arrives only via the vendored library's link closure (Flags.defines -> -D), so a successful compile
// proves the closure define reaches the Scala Native C compile.
#ifndef SNX_VENDORED
#error "SNX_VENDORED not defined - the vendored library's closure define did not reach the C compile"
#endif

int snx_glue(void) { return snx_answer(); }
