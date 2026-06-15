/* A non-static (external) global the program never references. Force-loading the archive twice would define snx_dup
 * twice and fail the link with a duplicate symbol, so a successful link proves the shared whole-archive requirement
 * was de-duplicated before it was rendered into the link. */
int snx_dup(void) { return 42; }
