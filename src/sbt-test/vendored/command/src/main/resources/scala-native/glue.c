#include "answer.h"

// #includes the vendored answer.h (proves the Command Artefacts' include dir folded to -I) and calls snx_answer from
// the built libanswer.a, which calls into libbase.a (proves both archives reached the native link).
int snx_glue(void) { return snx_answer(); }
