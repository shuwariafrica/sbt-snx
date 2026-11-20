/* Each string lands in the archive's object, so a check reads from the linked archive which build of the recipe it is. */
#ifndef SNX_VARIANT
#error "SNX_VARIANT must be defined by the recipe"
#endif
#define SNX_STR2(x) #x
#define SNX_STR(x) SNX_STR2(x)
const char snx_variant[] = "snx-variant-" SNX_STR(SNX_VARIANT);
#ifdef SNX_EXTRA
const char snx_extra[] = SNX_EXTRA;
#endif
#ifdef SNX_NOTE
const char snx_note[] = SNX_NOTE;
#endif
#if __has_include("overlay.h")
#include "overlay.h"
const char snx_overlay[] = SNX_OVERLAY;
#endif
int snx_answer(void) { return 42; }
