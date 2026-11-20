#include "answer.h"

// #includes the vendored answer.h (proves the recipe's include directory folded to -I) and calls snx_answer from
// the built libanswer, which calls into libbase (proves both archives reached the native link).
int snx_glue(void) { return snx_answer(); }
