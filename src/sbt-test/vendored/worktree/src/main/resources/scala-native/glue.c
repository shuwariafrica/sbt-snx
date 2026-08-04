#include "answer.h"

// #includes the vendored answer.h (proves the Command Artefacts' include dir folded to -I) and calls snx_answer from
// the built libanswer.a (proves the archive reached the native link).
int snx_glue(void) { return snx_answer(); }
