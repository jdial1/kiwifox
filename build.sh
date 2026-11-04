set -x
./mach build
./mach gradle fenix:assembleRelease
echo "BUILD_EXIT=$?"
find . -path '*fenix/app/outputs/apk/release/*.apk'
