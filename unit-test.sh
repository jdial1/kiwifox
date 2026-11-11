# Run the KiwiFox JVM unit tests (extractor + converter) against local clones of the catalogue repos.
set -x
export KIWIFOX_CATALOG_DIR=F:/kiwifox-dl/catalog
export KIWIFOX_AMO_DIR=F:/kiwifox-dl/amo/xpi
./mach gradle fenix:testDebugUnitTest --tests "org.mozilla.fenix.kiwifox.*" -i 2>&1 | grep -E "CATALOG|STYLE |AMO |FAILED|PASSED|BUILD|^e: |AssertionError|Test.*>" 
echo "TEST_EXIT=${PIPESTATUS[0]}"
