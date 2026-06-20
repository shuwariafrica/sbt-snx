/* Calls zlib's zlibVersion. zlib carries no @link annotation, so a consumer's link must add -lz itself - which it gets
 * from this library's SNX.libraries descriptor, never by hand. SNX_CORE arrives via the same descriptor's SNX.flags, so
 * a successful compile at the consumer proves both the library requirement and the define propagate. */
#ifndef SNX_CORE
#error "SNX_CORE not defined - core's SNX.flags define did not propagate to the consumer's compile"
#endif

extern const char *zlibVersion(void);

const char *snx_zlib_version(void) { return zlibVersion(); }
