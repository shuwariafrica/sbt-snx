#include "answer.h"

// SNX_ANSWER is compile-defined from ANSWER_VALUE, which AnswerModule.cmake sets - and that module is reachable only
// via the moduleOverrides directory on CMAKE_MODULE_PATH.
#ifndef SNX_ANSWER
#error "SNX_ANSWER not defined - AnswerModule (from moduleOverrides) did not reach cmake"
#endif

int snx_answer(void) { return SNX_ANSWER; }
