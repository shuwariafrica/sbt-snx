#include "answer.h"

// These defines arrive only via the vendored library's per-platform options (compileOptions / cOptions), so a
// successful compile proves the compile and c channels reach the Scala Native C compile.
#ifndef SNX_VENDORED_COMPILE
#error "SNX_VENDORED_COMPILE not defined - the vendored compile option did not reach the C compile"
#endif
#ifndef SNX_VENDORED_C
#error "SNX_VENDORED_C not defined - the vendored c option did not reach the C compile"
#endif

int snx_glue(void) { return snx_answer(); }
