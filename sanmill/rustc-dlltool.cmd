@echo off
rem RUSTC_WRAPPER used by sanmill/build.sh on Windows: the GNU host toolchain's own dlltool
rem needs a GNU assembler that rustup does not ship, so host build scripts (cc/jobserver ->
rem getrandom, which uses raw-dylib imports) fail to link. LLVM's dlltool from the NDK does
rem the same job without an assembler; its path arrives in SANMILL_DLLTOOL.
%* -C dlltool=%SANMILL_DLLTOOL%
