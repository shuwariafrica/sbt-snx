#include "answer.h"

// SNX_VIA_FLAG is compile-defined only when the plugin's per-platform CMake configure flag (-DSNX_VIA_FLAG=ON)
// reached cmake and enabled the option; a successful compile proves the vendored CMake flags PF reached configure.
#ifndef SNX_VIA_FLAG
#error "SNX_VIA_FLAG not defined - the vendored CMake configure flag did not reach cmake"
#endif

int snx_answer(void) { return 42; }
