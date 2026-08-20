import subprocess

Import("env")


def git_sha():
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "--short=7", "HEAD"],
            cwd=env.subst("$PROJECT_DIR"),
            stderr=subprocess.DEVNULL,
            text=True,
        ).strip() or "unknown"
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


env.Append(CPPDEFINES=[("DUCATI_GIT_SHA", env.StringifyMacro(git_sha()))])
