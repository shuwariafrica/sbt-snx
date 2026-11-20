#include "answer.h"

// #includes the vendored answer.h (proves the recipe's include directory folded to -I) and calls snx_answer from
// the built libanswer (proves the archive reached the native link).
int snx_glue(void) { return snx_answer(); }
