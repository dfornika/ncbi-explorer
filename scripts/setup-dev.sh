#!/usr/bin/env bash
set -euo pipefail

echo "=== Setting up Clojure dev environment ==="

# --- Java check ---
if ! command -v java &>/dev/null; then
  echo "ERROR: Java is required but not found. Install JDK 21+ first."
  exit 1
fi
echo "Java: $(java -version 2>&1 | head -1)"

# --- Clojure CLI ---
if command -v clojure &>/dev/null; then
  echo "Clojure CLI: $(clojure --version 2>&1)"
else
  echo "Installing Clojure CLI..."
  curl -sL -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
  chmod +x linux-install.sh
  sudo bash linux-install.sh
  rm linux-install.sh
  echo "Clojure CLI: $(clojure --version 2>&1)"
fi

# --- Babashka ---
if command -v bb &>/dev/null; then
  echo "Babashka: $(bb --version 2>&1)"
else
  echo "Installing Babashka..."
  curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
  chmod +x install
  sudo ./install
  rm install
  echo "Babashka: $(bb --version 2>&1)"
fi

# --- bbin ---
if command -v bbin &>/dev/null; then
  echo "bbin: $(bbin --version 2>&1 | tail -1)"
else
  echo "Installing bbin..."
  curl -sL https://raw.githubusercontent.com/babashka/bbin/main/bbin -o /tmp/bbin
  chmod +x /tmp/bbin
  sudo mv /tmp/bbin /usr/local/bin/bbin

  # bbin uses bb's deps.clj which may hit TLS issues downloading clojure tools;
  # pre-fetch the zip via curl if needed.
  TOOLS_VERSION=$(bbin --version 2>&1 | grep -oP 'ClojureTools/clojure-tools-\K[^.]+\.[^.]+\.[^.]+\.[^.]+' || true)
  if [ -z "$TOOLS_VERSION" ]; then
    TOOLS_VERSION=$(bbin --version 2>&1 | grep -oP '/\K[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)
  fi
  if [ -n "$TOOLS_VERSION" ] && ! bbin --version &>/dev/null; then
    TOOLS_DIR="$HOME/.deps.clj/$TOOLS_VERSION/ClojureTools"
    mkdir -p "$TOOLS_DIR"
    curl -sL "https://github.com/clojure/brew-install/releases/download/$TOOLS_VERSION/clojure-tools.zip" \
      -o "$TOOLS_DIR/clojure-tools.zip"
  fi

  echo "bbin: $(bbin --version 2>&1 | tail -1)"
fi

export PATH="$HOME/.local/bin:$PATH"

# --- clojure-mcp-light tools ---
CMCPL_TAG="v0.2.2"
CMCPL_REPO="https://github.com/bhauman/clojure-mcp-light.git"

if ! command -v clj-paren-repair-claude-hook &>/dev/null; then
  echo "Installing clj-paren-repair-claude-hook..."
  bbin install "$CMCPL_REPO" --tag "$CMCPL_TAG"
fi

if ! command -v clj-nrepl-eval &>/dev/null; then
  echo "Installing clj-nrepl-eval..."
  bbin install "$CMCPL_REPO" --tag "$CMCPL_TAG" \
    --as clj-nrepl-eval --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]'
fi

if ! command -v clj-paren-repair &>/dev/null; then
  echo "Installing clj-paren-repair..."
  bbin install "$CMCPL_REPO" --tag "$CMCPL_TAG" \
    --as clj-paren-repair --main-opts '["-m" "clojure-mcp-light.paren-repair"]'
fi

echo "clojure-mcp-light tools installed."

# --- Project dependencies ---
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "Downloading project dependencies..."
cd "$PROJECT_DIR"
clojure -M:dev:nrepl -P

echo ""
echo "=== Dev environment ready ==="
echo "  Start nREPL:  clojure -M:nrepl    (port 7888)"
echo "  Eval code:    clj-nrepl-eval -p 7888 '(+ 1 2)'"
echo "  One-shot:     clojure -M:dev -e '(require (quote user))'"
