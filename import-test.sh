# History importer checks: dated writes against the real places store (android-components), the
# parser/importer pipeline (Fenix), then the release Kotlin compile (-Werror).
# Run with: run-mach.cmd /f/kiwifox/import-test.sh   (from PowerShell: cmd /c "F:\kiwifox\run-mach.cmd ...")
# The first test needs application-services' native test library, which ships for Linux and macOS only:
# on Windows it fails with "jnidispatch.dll not found" (2026-11-13) and only the other two steps count.
set -x
./mach gradle :components:browser-storage-sync:testDebugUnitTest --tests "mozilla.components.browser.storage.sync.KiwiHistoryImportTest" -i 2>&1 | grep -E "KiwiHistoryImportTest|FAILED|PASSED|BUILD|^e: |^w: |AssertionError|Exception|expected"
echo "AC_TEST_EXIT=${PIPESTATUS[0]}"
./mach gradle fenix:testDebugUnitTest --tests "org.mozilla.fenix.kiwifox.ChromeHistoryImportTest" -i 2>&1 | grep -E "ChromeHistoryImportTest|FAILED|PASSED|BUILD|^e: |^w: |AssertionError|Exception|expected"
echo "FENIX_TEST_EXIT=${PIPESTATUS[0]}"
./mach gradle fenix:compileReleaseKotlin 2>&1 | grep -E "BUILD|^e: |^w: |FAILED"
echo "COMPILE_EXIT=${PIPESTATUS[0]}"
