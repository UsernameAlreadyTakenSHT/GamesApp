// Stand-in for Windows' <direct.h> so db_gen.c builds for Linux/Android.
#pragma once
#include <sys/stat.h>
#define _mkdir(d) mkdir((d), 0755)
