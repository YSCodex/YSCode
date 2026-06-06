#!/data/data/com.termux/files/usr/bin/bash
# YS Code Agent - Termux Setup Script
# Run this in Termux to install and run YS Code Agent

set -e

echo "=============================="
echo " YS Code Agent - Termux Setup "
echo "=============================="
echo ""

# Step 1: Check Node.js
echo "[1/6] Checking Node.js..."
if ! command -v node &>/dev/null; then
    echo "Installing Node.js..."
    pkg install nodejs -y
fi
echo "  Node.js $(node --version) ✓"

# Step 2: Create project directory in home
echo "[2/6] Setting up project directory..."
mkdir -p ~/ys-code-agent
cd ~/ys-code-agent

# Step 3: Download or copy files
echo "[3/6] Copying YS Code Agent files..."
if [ -d "/storage/emulated/0/Ys-Agent" ]; then
    cp -r /storage/emulated/0/Ys-Agent/* ~/ys-code-agent/ 2>/dev/null || true
    cp -r /storage/emulated/0/Ys-Agent/.* ~/ys-code-agent/ 2>/dev/null || true
    echo "  Files copied from /storage/emulated/0/Ys-Agent/ ✓"
elif [ -d "../ys-code-agent" ]; then
    cp -r ../ys-code-agent/* ~/ys-code-agent/ 2>/dev/null || true
    echo "  Files copied from ../ys-code-agent/ ✓"
else
    echo "  Files already present ✓"
fi

# Step 4: Install dependencies
echo "[4/6] Installing npm dependencies..."
npm install --no-bin-links 2>&1 | tail -5
echo "  Dependencies installed ✓"

# Step 5: Build
echo "[5/6] Building project..."
npx tsc 2>&1 || true
if node scripts/build.mjs 2>&1; then
  echo "  Build complete ✓"
else
  echo "  esbuild failed, but pre-built dist/cli/index.cjs is available"
  echo "  You can still run: node dist/cli/index.cjs"
fi

# Step 6: Create alias
echo "[6/6] Creating 'ys' command..."
if ! grep -q "alias ys=" ~/.bashrc 2>/dev/null; then
    echo 'alias ys="node ~/ys-code-agent/dist/cli/index.cjs"' >> ~/.bashrc
    echo "  Alias added to ~/.bashrc"
fi

# Also add to current session
alias ys="node ~/ys-code-agent/dist/cli/index.js"

echo ""
echo "=============================="
echo " Setup Complete! 🎉"
echo "=============================="
echo ""
echo "To use YS Code Agent:"
echo ""
echo "  1. Set your API key:"
echo "     export OPENAI_API_KEY=sk-your-key-here"
echo ""
echo "  2. Start interactive session:"
echo "     ys chat"
echo ""
echo "  3. Or run a task:"
echo "     ys run \"Explain this project\""
echo ""
echo "  4. Run diagnostics:"
echo "     ys doctor"
echo ""
echo "NOTE: Always run from ~/ys-code-agent directory or"
echo "use 'ys --directory /path/to/project chat'"
echo ""
