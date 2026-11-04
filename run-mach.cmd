@ECHO OFF
REM Run a command inside the MozillaBuild (MSYS2) shell non-interactively.
REM Usage: run-mach.cmd <firefox-src-dir> "<bash command>"
SET MOZILLABUILD=F:\mozilla-build\
SET USE_MINTTY=0
SET MOZBUILD_STATE_PATH=F:\mozilla-state
SET MOZCONFIG=F:\kiwifox\mozconfig
SET CHERE_INVOKING=1
CD /D %1
%MOZILLABUILD%msys2\msys2_shell.cmd -no-start -defterm -full-path -here -c %2
