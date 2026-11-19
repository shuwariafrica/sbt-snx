#include "multi.h"

// #includes the installed multi.h (proves the staged include dir folded to -I) and calls multi_top from zz_top, whose
// chain reaches every archive the project installs.
int snx_glue(void) { return multi_top(); }
