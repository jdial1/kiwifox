@ECHO OFF
REM Run a bash script inside the MozillaBuild (MSYS2) shell non-interactively.
REM Usage: run-mach.cmd <script.sh>   (script runs with cwd = Firefox source dir)
SET MOZILLABUILD=F:\mozilla-build\
SET USE_MINTTY=0
SET MOZBUILD_STATE_PATH=F:\mozilla-state
SET MOZCONFIG=F:\kiwifox\mozconfig
SET CHERE_INVOKING=1
CD /D F:\kiwifox\firefox-FIREFOX_159_0_1_RELEASE
%MOZILLABUILD%msys2\msys2_shell.cmd -no-start -defterm -full-path -here -c "bash %1"
