/* Calls zlib's zlibVersion. zlib carries no @link annotation, so a consumer's link must add -lz itself - which it gets
 * from this library's SNX.usage descriptor, never by hand. */
extern const char *zlibVersion(void);

const char *snx_zlib_version(void) { return zlibVersion(); }
