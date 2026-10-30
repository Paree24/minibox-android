// JUCE 8.0.6 includes <sys/dir.h> on Android; NDK r29 no longer ships it. It was only ever an alias of <dirent.h>.
#pragma once
#include <dirent.h>
