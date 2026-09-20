/* Android (bionic) gives new threads a 1 MB stack; deep alpha-beta recursion needs more.
 * Linked with -Wl,--wrap=pthread_create so every thread gets at least 64 MB. */
#include <pthread.h>
#include <stddef.h>
int __real_pthread_create(pthread_t*, const pthread_attr_t*, void* (*)(void*), void*);
int __wrap_pthread_create(pthread_t* t, const pthread_attr_t* a, void* (*f)(void*), void* arg) {
    pthread_attr_t attr;
    if (a) attr = *a; else pthread_attr_init(&attr);
    size_t ss = 0;
    pthread_attr_getstacksize(&attr, &ss);
    if (ss < (64u << 20)) pthread_attr_setstacksize(&attr, 64u << 20);
    return __real_pthread_create(t, &attr, f, arg);
}
