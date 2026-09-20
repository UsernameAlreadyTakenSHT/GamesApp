//go:build !amd64

// Stubs for the AVX2 kernels on non-amd64 targets. Rodent V only calls them when
// cpu.X86.HasAVX2 is true, which is never the case here; the scalar paths are used.
// Copied next to the sources by rodent/build.sh (upstream lacks this file).
package main

func addSingleAVX2_64(a, w *int16)   { panic("avx2") }
func subSingleAVX2_64(a, w *int16)   { panic("avx2") }
func addSingleAVX2_128(a, w *int16)  { panic("avx2") }
func subSingleAVX2_128(a, w *int16)  { panic("avx2") }
func addSingleAVX2_256(a, w *int16)  { panic("avx2") }
func subSingleAVX2_256(a, w *int16)  { panic("avx2") }
func addSingleAVX2_384(a, w *int16)  { panic("avx2") }
func subSingleAVX2_384(a, w *int16)  { panic("avx2") }
func addSingleAVX2_512(a, w *int16)  { panic("avx2") }
func subSingleAVX2_512(a, w *int16)  { panic("avx2") }
func addSingleAVX2_768(a, w *int16)  { panic("avx2") }
func subSingleAVX2_768(a, w *int16)  { panic("avx2") }
func addSingleAVX2_1024(a, w *int16) { panic("avx2") }
func subSingleAVX2_1024(a, w *int16) { panic("avx2") }

func captureAVX2_64(a0, a1, wTo0, wFrom0, wCap0, wTo1, wFrom1, wCap1 *int16)   { panic("avx2") }
func captureAVX2_128(a0, a1, wTo0, wFrom0, wCap0, wTo1, wFrom1, wCap1 *int16)  { panic("avx2") }
func captureAVX2_256(a0, a1, wTo0, wFrom0, wCap0, wTo1, wFrom1, wCap1 *int16)  { panic("avx2") }
func captureAVX2_384(a0, a1, wTo0, wFrom0, wCap0, wTo1, wFrom1, wCap1 *int16)  { panic("avx2") }
func captureAVX2_512(a0, a1, wTo0, wFrom0, wCap0, wTo1, wFrom1, wCap1 *int16)  { panic("avx2") }
func captureAVX2_768(a0, a1, wTo0, wFrom0, wCap0, wTo1, wFrom1, wCap1 *int16)  { panic("avx2") }
func captureAVX2_1024(a0, a1, wTo0, wFrom0, wCap0, wTo1, wFrom1, wCap1 *int16) { panic("avx2") }

func moveAVX2_64(a0, a1, wFrom0, wTo0, wFrom1, wTo1 *int16)   { panic("avx2") }
func moveAVX2_128(a0, a1, wFrom0, wTo0, wFrom1, wTo1 *int16)  { panic("avx2") }
func moveAVX2_256(a0, a1, wFrom0, wTo0, wFrom1, wTo1 *int16)  { panic("avx2") }
func moveAVX2_384(a0, a1, wFrom0, wTo0, wFrom1, wTo1 *int16)  { panic("avx2") }
func moveAVX2_512(a0, a1, wFrom0, wTo0, wFrom1, wTo1 *int16)  { panic("avx2") }
func moveAVX2_768(a0, a1, wFrom0, wTo0, wFrom1, wTo1 *int16)  { panic("avx2") }
func moveAVX2_1024(a0, a1, wFrom0, wTo0, wFrom1, wTo1 *int16) { panic("avx2") }

func castleAVX2_64(a0, a1, wKFrom0, wKTo0, wRFrom0, wRTo0, wKFrom1, wKTo1, wRFrom1, wRTo1 *int16)   { panic("avx2") }
func castleAVX2_128(a0, a1, wKFrom0, wKTo0, wRFrom0, wRTo0, wKFrom1, wKTo1, wRFrom1, wRTo1 *int16)  { panic("avx2") }
func castleAVX2_256(a0, a1, wKFrom0, wKTo0, wRFrom0, wRTo0, wKFrom1, wKTo1, wRFrom1, wRTo1 *int16)  { panic("avx2") }
func castleAVX2_384(a0, a1, wKFrom0, wKTo0, wRFrom0, wRTo0, wKFrom1, wKTo1, wRFrom1, wRTo1 *int16)  { panic("avx2") }
func castleAVX2_512(a0, a1, wKFrom0, wKTo0, wRFrom0, wRTo0, wKFrom1, wKTo1, wRFrom1, wRTo1 *int16)  { panic("avx2") }
func castleAVX2_768(a0, a1, wKFrom0, wKTo0, wRFrom0, wRTo0, wKFrom1, wKTo1, wRFrom1, wRTo1 *int16)  { panic("avx2") }
func castleAVX2_1024(a0, a1, wKFrom0, wKTo0, wRFrom0, wRTo0, wKFrom1, wKTo1, wRFrom1, wRTo1 *int16) { panic("avx2") }

func moveAVX2_512_3op(dst0, src0, dst1, src1, wFrom0, wTo0, wFrom1, wTo1 *int16)                    { panic("avx2") }
func captureAVX2_512_3op(dst0, src0, dst1, src1, wTo0, wFrom0, wCap0, wTo1, wFrom1, wCap1 *int16) { panic("avx2") }
func castleAVX2_512_3op(dst0, src0, dst1, src1, wKFrom0, wKTo0, wRFrom0, wRTo0, wKFrom1, wKTo1, wRFrom1, wRTo1 *int16) {
	panic("avx2")
}

func getEvalAVX2_64(a0, a1, w0, w1 *int16, sum *int32)   { panic("avx2") }
func getEvalAVX2_128(a0, a1, w0, w1 *int16, sum *int32)  { panic("avx2") }
func getEvalAVX2_256(a0, a1, w0, w1 *int16, sum *int32)  { panic("avx2") }
func getEvalAVX2_384(a0, a1, w0, w1 *int16, sum *int32)  { panic("avx2") }
func getEvalAVX2_512(a0, a1, w0, w1 *int16, sum *int32)  { panic("avx2") }
func getEvalAVX2_768(a0, a1, w0, w1 *int16, sum *int32)  { panic("avx2") }
func getEvalAVX2_1024(a0, a1, w0, w1 *int16, sum *int32) { panic("avx2") }
