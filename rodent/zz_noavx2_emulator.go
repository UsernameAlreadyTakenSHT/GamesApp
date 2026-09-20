//go:build amd64

// Forces the scalar NNUE path. Rodent's hand-written AVX2 kernels fault inside the
// Android emulator (SIGSEGV in moveAVX2_768 although /proc/cpuinfo advertises AVX2), and
// x86_64 builds only ever run there. Named so its init() runs after nnue.go's (and without a _GOOS suffix, which Go would treat as a build constraint).
// Copied next to the sources by rodent/build.sh for the x86_64 build only.
package main

import "fmt"

func init() {
	hasAVX2 = false
	fmt.Println("info string AVX2 kernels disabled (Android x86_64 build)")
}
