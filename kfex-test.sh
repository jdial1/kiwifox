set -x
./mach gradle fenix:testDebugUnitTest --tests "org.mozilla.fenix.kiwifox.KiwiCryptoTest" -i 2>&1 | grep -E "KiwiCryptoTest|FAILED|PASSED|BUILD|^e: |^w: |AssertionError|KfexError|Exception"
echo "EXIT=${PIPESTATUS[0]}"
