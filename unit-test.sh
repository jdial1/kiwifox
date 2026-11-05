# Run the KiwiFox JVM unit tests (extractor + converter) against local clones of the catalogue repos.
set -x
export KIWIFOX_CATALOG_DIR=F:/kiwifox-dl/catalog
./mach gradle fenix:testDebugUnitTest --tests "org.mozilla.fenix.kiwifox.*" -i 2>&1 | grep -E "CATALOG|FAILED|PASSED|BUILD|^e: |AssertionError|Test.*>" 
echo "TEST_EXIT=${PIPESTATUS[0]}"
