#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOOK_PATH="$REPO_ROOT/.git/hooks/pre-push"

cat << 'EOF' > "$HOOK_PATH"
#!/usr/bin/env bash
set -euo pipefail

echo "🛡️ [MyTypeless Pre-Push Gate] Running unit & API contract tests before push..."
if [ -d "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
fi

./gradlew testDebugUnitTest
echo "✅ [MyTypeless Pre-Push Gate] All tests passed! Proceeding with push."
EOF

chmod +x "$HOOK_PATH"
echo "✅ Installed pre-push hook at $HOOK_PATH"
