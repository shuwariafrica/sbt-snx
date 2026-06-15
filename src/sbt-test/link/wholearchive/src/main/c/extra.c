#include <stdio.h>
#include <stdlib.h>

/* No symbol in this translation unit is referenced by the program, so the linker drops the whole object unless the
 * archive is force-linked. When it is, this constructor runs at startup and writes the sentinel. */
__attribute__((constructor)) static void snx_whole_archive_marker(void) {
  const char *path = getenv("SNX_WHOLEARCHIVE_SENTINEL");
  if (path != NULL) {
    FILE *file = fopen(path, "w");
    if (file != NULL) {
      fputs("ok", file);
      fclose(file);
    }
  }
}
