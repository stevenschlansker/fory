#!/usr/bin/env bash

# This script is derived from https://github.com/ray-project/ray/blob/5ce25a57a0949673d17f3a8784f05b2d65290524/ci/lint/format.sh.

# Formats changed files (or all files with --all). Run this locally before pushing.
# Required formatters are checked, not installed; a missing one fails with the fix
# command. Run `./format.sh --install` to provision them.

# Cause the script to exit if a single command fails
set -euox pipefail

SHELLCHECK_VERSION_REQUIRED="0.7.1"

install_nodejs() {
  #install nodejs
  filename="node-v16.17.1-linux-x64"
  pkg="$filename.tar.gz"
  NODE_URL="https://nodejs.org/dist/v16.17.1/$pkg"
  echo "start to download $pkg from $NODE_URL"
  wget -q $NODE_URL -O "$pkg"
  echo "download $pkg succeeds"
  tar -C . -xzf "$pkg"
  export PATH="$(pwd)/$filename/bin:$PATH"
  node -v
  npm -v
}

# this stops git rev-parse from failing if we run this from the .git directory
builtin cd "$(dirname "${BASH_SOURCE:-$0}")"

ROOT="$(git rev-parse --show-toplevel)"
builtin cd "$ROOT" || exit 1

# params: tool name, tool version, required version
tool_version_check() {
    if [ "$2" != "$3" ]; then
        echo "WARNING: Fory uses $1 $3, You currently are using $2. This might generate different results."
    fi
}

if command -v shellcheck >/dev/null; then
    SHELLCHECK_VERSION=$(shellcheck --version | awk '/^version:/ {print $2}')
    tool_version_check "shellcheck" "$SHELLCHECK_VERSION" "$SHELLCHECK_VERSION_REQUIRED"
else
    echo "INFO: Fory uses shellcheck for shell scripts, which is not installed. You may install shellcheck=$SHELLCHECK_VERSION_REQUIRED with your system package manager."
fi

# Install a Python package via whichever pip front-end is available. Bare `pip` is not on PATH
# in many environments (Debian/Ubuntu, many CI containers) where only `pip3` or `python3 -m pip`
# work; pick whichever exists rather than hard-failing.
pip_install() {
    if command -v pip >/dev/null; then
        pip install "$@"
    elif command -v pip3 >/dev/null; then
        pip3 install "$@"
    elif command -v python3 >/dev/null; then
        python3 -m pip install "$@"
    else
        echo "ERROR: no pip / pip3 / python3 available to install $*" >&2
        return 1
    fi
}

CLANG_FORMAT_VERSION_REQUIRED="18.1.8"

# Every formatter is checked, never installed, on the format paths, so a plain
# run never mutates the environment; a missing tool fails with its fix command.
# `./format.sh --install` provisions the pip/npm tools (clang-format, ruff,
# node/eslint, prettier). Maven, Go, .NET, and swiftlint are expected from the
# system or CI runner and are only verified here, not installed.

require_ruff() {
    if ! [ -x "$(command -v ruff)" ]; then
        echo "ERROR: ruff is not installed. Install with: pip install ruff" \
             "(or run ./format.sh --install)." >&2
        return 1
    fi
}

# Extract the active clang-format X.Y.Z version. The [^0-9] anchor keeps a
# two-digit major intact ("18.1.8", not "8.1.8") and skips vendor/build suffixes.
clang_format_version() {
    clang-format --version | sed -n 's/.*[^0-9]\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\).*/\1/p' | head -n1
}

require_clang_format() {
    local required="$CLANG_FORMAT_VERSION_REQUIRED"
    if ! command -v clang-format >/dev/null; then
        echo "ERROR: clang-format is not installed. Install clang-format $required" \
             "with: pip install clang-format==$required (or run ./format.sh --install)." >&2
        return 1
    fi
    local installed
    installed=$(clang_format_version)
    if [ "$installed" != "$required" ]; then
        echo "ERROR: Fory uses clang-format $required but $installed is active." \
             "Install with: pip install clang-format==$required (or run ./format.sh --install)." >&2
        return 1
    fi
}

require_node() {
    if ! command -v node >/dev/null; then
        echo "ERROR: node is not installed. Run ./format.sh --install to install it." >&2
        return 1
    fi
}

require_node_and_eslint() {
    require_node || return 1
    if [ ! -f "$ROOT/javascript/node_modules/.bin/eslint" ]; then
        echo "ERROR: eslint is not installed. Run ./format.sh --install, or" \
             "'npm install' in $ROOT/javascript." >&2
        return 1
    fi
}

require_prettier() {
    if ! command -v prettier >/dev/null; then
        echo "ERROR: prettier is not installed. Run ./format.sh --install, or" \
             "'npm install -g prettier'." >&2
        return 1
    fi
}

require_maven() {
    if ! command -v mvn >/dev/null; then
        echo "ERROR: mvn is not installed. Install Maven from https://maven.apache.org/." >&2
        return 1
    fi
}

require_gofmt() {
    if ! command -v gofmt >/dev/null; then
        echo "ERROR: gofmt is not installed. Install Go from https://go.dev/." >&2
        return 1
    fi
}

require_dotnet() {
    if ! command -v dotnet >/dev/null; then
        echo "ERROR: dotnet is not installed. Install the .NET SDK from" \
             "https://dotnet.microsoft.com/download." >&2
        return 1
    fi
}

require_swiftlint() {
    if ! command -v swiftlint >/dev/null; then
        echo "ERROR: swiftlint is not installed. Install it with 'brew install swiftlint'" \
             "or from https://github.com/realm/SwiftLint." >&2
        return 1
    fi
}

# Install the formatter tools. Only reached via `./format.sh --install`.
# Each step is validated under set -e so a broken install can't pass silently.
install_deps() {
    echo "Installing clang-format $CLANG_FORMAT_VERSION_REQUIRED..."
    pip_install "clang-format==$CLANG_FORMAT_VERSION_REQUIRED"
    hash -r
    local scripts
    scripts=$(python3 -c "import sysconfig; print(sysconfig.get_path('scripts'))")
    export PATH="$scripts:$PATH"
    require_clang_format

    echo "Installing ruff..."
    pip_install ruff
    hash -r
    require_ruff

    if ! command -v node >/dev/null; then
        echo "INFO: node is not installed, start to install it"
        install_nodejs
    fi
    require_node

    echo "Installing eslint (npm install in javascript)..."
    pushd "$ROOT/javascript"
    npm install
    popd

    echo "Installing prettier globally..."
    npm install -g prettier

    echo "Formatter tools installed."
}

SHELLCHECK_FLAGS=(
  --exclude=1090  # "Can't follow non-constant source. Use a directive to specify location."
  --exclude=1091  # "Not following {file} due to some error"
  --exclude=2207  # "Prefer mapfile or read -a to split command output (or quote to avoid splitting)." -- these aren't compatible with macOS's old Bash
)

GIT_LS_EXCLUDES=(
  ':(exclude)src/thirdparty/'
)

# Format specified files
format_files() {
    local shell_files=() python_files=() bazel_files=()

    local name
    for name in "$@"; do
      local base="${name%.*}"
      local suffix="${name#${base}}"

      local shebang=""
      read -r shebang < "${name}" || true
      case "${shebang}" in
        '#!'*)
          shebang="${shebang#/usr/bin/env }"
          shebang="${shebang%% *}"
          shebang="${shebang##*/}"
          ;;
      esac

      if [ "${base}" = "WORKSPACE" ] || [ "${base}" = "BUILD" ] || [ "${suffix}" = ".BUILD" ] || [ "${suffix}" = ".bazel" ] || [ "${suffix}" = ".bzl" ]; then
        bazel_files+=("${name}")
      elif [ -z "${suffix}" ] && [ "${shebang}" != "${shebang#python}" ] || [ "${suffix}" != "${suffix#.py}" ]; then
        python_files+=("${name}")
      elif [ -z "${suffix}" ] && [ "${shebang}" != "${shebang%sh}" ] || [ "${suffix}" != "${suffix#.sh}" ]; then
        shell_files+=("${name}")
      else
        echo "error: failed to determine file type: ${name}" 1>&2
        return 1
      fi
    done

    if [ 0 -lt "${#python_files[@]}" ]; then
      require_ruff || exit 1
      ruff format "${python_files[@]}"
      ruff check --fix "${python_files[@]}"
    fi
}

format_all_scripts() {
    require_ruff || exit 1
    echo "$(date)" "Ruff format...."
    git ls-files -- '*.py' "${GIT_LS_EXCLUDES[@]}" | xargs -P 10 \
      ruff format

    echo "$(date)" "Ruff check...."
    git ls-files -- '*.py' "${GIT_LS_EXCLUDES[@]}" | xargs \
      ruff check --fix
}

format_java() {
    require_maven || exit 1
    cd "$ROOT/java"
    mvn -T10 --no-transfer-progress spotless:apply
    mvn -T10 --no-transfer-progress checkstyle:check
    mvn -T10 --no-transfer-progress install -DskipTests
    cd "$ROOT/benchmarks/java"
    mvn -T10 --no-transfer-progress spotless:apply
    cd "$ROOT/integration_tests"
    dirs=("graalvm_tests" "jdk_compatibility_tests")
    for d in "${dirs[@]}" ; do
      pushd "$d"
        mvn -T10 --no-transfer-progress spotless:apply
      popd
    done
    cd "$ROOT"
}

format_cpp() {
    require_clang_format || exit 1
    echo "$(date)" "clang-format C++ files...."
    git ls-files -- '*.cc' '*.h' "${GIT_LS_EXCLUDES[@]}" | xargs -P 5 clang-format -i
    echo "$(date)" "C++ formatting done!"
}

format_python() {
    require_ruff || exit 1
    echo "$(date)" "Ruff format Python files...."
    git ls-files -- '*.py' "${GIT_LS_EXCLUDES[@]}" | xargs -P 10 ruff format
    git ls-files -- '*.py' "${GIT_LS_EXCLUDES[@]}" | xargs ruff check --fix
    echo "$(date)" "Python formatting done!"
}

format_go() {
    require_gofmt || exit 1
    echo "$(date)" "gofmt format Go files...."
    git ls-files -- '*.go' "${GIT_LS_EXCLUDES[@]}" | xargs -P 5 gofmt -w
    echo "$(date)" "Go formatting done!"
}

format_csharp() {
    require_dotnet || exit 1
    echo "$(date)" "dotnet format C# files...."
    pushd "$ROOT/csharp"
    dotnet format Fory.sln \
      --exclude src/Fory.Generator/AnalyzerReleases.Shipped.md \
      --exclude src/Fory.Generator/AnalyzerReleases.Unshipped.md
    popd
    echo "$(date)" "C# formatting done!"
}

format_swift() {
    require_swiftlint || exit 1
    echo "$(date)" "SwiftLint check Swift files...."
    pushd "$ROOT/swift"
    swiftlint lint --config .swiftlint.yml
    popd
    echo "$(date)" "SwiftLint done!"
}

# Format all files, and print the diff to stdout for travis.
format_all() {
    format_all_scripts "${@}"

    echo "$(date)" "clang-format...."
    require_clang_format || exit 1
    git ls-files -- '*.cc' '*.h' '*.proto' "${GIT_LS_EXCLUDES[@]}" | xargs -P 5 clang-format -i

    echo "$(date)" "format java...."
    format_java

    echo "$(date)" "format javascript...."
    require_node_and_eslint || exit 1
    pushd "$ROOT"
    git ls-files -- '*.ts' "${GIT_LS_EXCLUDES[@]}" | xargs -P 5 node ./javascript/node_modules/.bin/eslint
    popd

    echo "$(date)" "format go...."
    format_go

    echo "$(date)" "format csharp...."
    format_csharp

    # The dedicated macOS Swift CI job owns swiftlint; FORMAT_SKIP_SWIFT=1 lets the
    # Linux lint job skip it (and the brew-on-Linux install), same as format_changed.
    if [ -z "${FORMAT_SKIP_SWIFT-}" ]; then
        echo "$(date)" "lint swift...."
        format_swift
    fi

    echo "$(date)" "done!"
}

# Format files that differ from main branch. Ignores dirs that are not slated
# for autoformat yet.
format_changed() {
    # The `if` guard ensures that the list of filenames is not empty, which
    # could cause the formatter to receive 0 positional arguments, making
    # it error.
    #
    # `diff-filter=ACRM` and $MERGEBASE is to ensure we only format files that
    # exist on both branches.
    MERGEBASE="$(git merge-base origin/main HEAD)"

    if ! git diff --diff-filter=ACRM --quiet --exit-code "$MERGEBASE" -- '*.py' &>/dev/null; then
        require_ruff || exit 1
        git diff --name-only --diff-filter=ACRM "$MERGEBASE" -- '*.py' | xargs -P 5 \
            ruff format
        git diff --name-only --diff-filter=ACRM "$MERGEBASE" -- '*.py' | xargs -P 5 \
            ruff check --fix
    fi

    if ! git diff --diff-filter=ACRM --quiet --exit-code "$MERGEBASE" -- '*.cc' '*.h' &>/dev/null; then
        require_clang_format || exit 1
        git diff --name-only --diff-filter=ACRM "$MERGEBASE" -- '*.cc' '*.h' | xargs -P 5 \
             clang-format -i
    fi

    if ! git diff --diff-filter=ACRM --quiet --exit-code "$MERGEBASE" -- '*.java' &>/dev/null; then
        format_java
    fi

    if ! git diff --diff-filter=ACRM --quiet --exit-code "$MERGEBASE" -- '*.go' &>/dev/null; then
        require_gofmt || exit 1
        git diff --name-only --diff-filter=ACRM "$MERGEBASE" -- '*.go' | xargs -P 5 \
              gofmt -w
    fi

    if [ -n "$(git diff --name-only --diff-filter=ACRM "$MERGEBASE" -- csharp || true)" ]; then
        format_csharp
    fi

    if ! git diff --diff-filter=ACRM --quiet --exit-code "$MERGEBASE" -- '*.ts' &>/dev/null; then
        require_node_and_eslint || exit 1
        pushd "$ROOT"
        git diff --name-only --diff-filter=ACRM "$MERGEBASE" -- '*.ts' | xargs -P 5 \
              node ./javascript/node_modules/.bin/eslint
        popd
    fi

    if ! git diff --diff-filter=ACRM --quiet --exit-code "$MERGEBASE" -- '*.md' &>/dev/null; then
        require_prettier || exit 1
        pushd "$ROOT"
        # Fix only the changed markdown files, except analyzer release tracking
        # files. Exclude symlinks (for example CLAUDE.md) because prettier fails
        # on explicitly passed symlink paths. Collect into an array first so we
        # skip prettier entirely when filtering leaves nothing (portable across
        # GNU and BSD xargs, which differ on empty input).
        local md_files=()
        while IFS= read -r -d '' file; do
            if [ ! -L "$file" ]; then
                md_files+=("$file")
            fi
        done < <(git diff -z --name-only --diff-filter=ACRM "$MERGEBASE" -- '*.md' \
            ':!:csharp/src/Fory.Generator/AnalyzerReleases.Shipped.md' \
            ':!:csharp/src/Fory.Generator/AnalyzerReleases.Unshipped.md')
        if [ 0 -lt "${#md_files[@]}" ]; then
            prettier --write "${md_files[@]}"
        fi
        popd
    fi

    # The dedicated macOS Swift CI job owns swiftlint; the Linux lint job sets
    # FORMAT_SKIP_SWIFT=1 to avoid a duplicate run (and a brew-on-Linux install).
    if [ -z "${FORMAT_SKIP_SWIFT-}" ] \
        && ! git diff --diff-filter=ACRM --quiet --exit-code "$MERGEBASE" -- 'swift' &>/dev/null; then
        format_swift
    fi
}


# Fetch main (so the merge-base diff works) and format only changed files. This
# is the default entry point; `--install-and-check` reuses it after installing.
fetch_and_format_changed() {
    # Add the origin remote if it doesn't exist
    if ! git remote -v | grep -q origin; then
        git remote add 'origin' 'https://github.com/apache/fory.git'
    fi

    # use unshallow fetch for `git merge-base origin/main HEAD` to work.
    # Only fetch main since that's the branch we're diffing against.
    git fetch origin main --unshallow || true

    echo "Format only the files that changed in last commit."
    format_changed
}

# This flag formats individual files. --files *must* be the first command line
# arg to use this option.
if [ "${1-}" == '--install' ]; then
    install_deps
    exit 0
elif [ "${1-}" == '--install-and-check' ]; then
    # Install then check in one process so the PATH that install_deps exports
    # (the pip scripts dir holding clang-format) is visible to the check pass.
    install_deps
    fetch_and_format_changed
elif [ "${1-}" == '--files' ]; then
    format_files "${@:2}"
# If `--all` or `--scripts` are passed, then any further arguments are ignored.
# Format the entire python directory and other scripts.
elif [ "${1-}" == '--all-scripts' ]; then
    format_all_scripts "${@}"
    if [ -n "${FORMAT_SH_PRINT_DIFF-}" ]; then git --no-pager diff; fi
# Format the all Python, C++, Java and other script files.
elif [ "${1-}" == '--all' ]; then
    format_all "${@}"
    if [ -n "${FORMAT_SH_PRINT_DIFF-}" ]; then git --no-pager diff; fi
elif [ "${1-}" == '--java' ]; then
    format_java
elif [ "${1-}" == '--cpp' ]; then
    format_cpp
elif [ "${1-}" == '--python' ]; then
    format_python
elif [ "${1-}" == '--go' ]; then
    format_go
elif [ "${1-}" == '--swift' ]; then
    format_swift
elif [ "${1-}" == '--csharp' ]; then
    format_csharp
else
    fetch_and_format_changed
fi

# Ensure import ordering
# Make sure that for every import psutil; import setproctitle
# There's a import ray above it.

PYTHON_EXECUTABLE=${PYTHON_EXECUTABLE:-python}

if ! git diff --quiet &>/dev/null; then
    echo 'Reformatted changed files. Please review and stage the changes.'
    echo 'Files updated:'
    echo

    git --no-pager diff

    exit 1
fi
