#!/usr/bin/env bash
set -euo pipefail

echo "=== Setting up bioinformatics tools (conda + bioconda) ==="

MINIFORGE_DIR="${MINIFORGE_DIR:-$HOME/miniforge3}"

# --- Miniforge ---
if [ -x "$MINIFORGE_DIR/bin/conda" ]; then
  echo "Miniforge already installed at $MINIFORGE_DIR"
else
  echo "Installing Miniforge..."
  curl -sL -o /tmp/miniforge.sh \
    "https://github.com/conda-forge/miniforge/releases/latest/download/Miniforge3-Linux-x86_64.sh"
  bash /tmp/miniforge.sh -b -p "$MINIFORGE_DIR"
  rm /tmp/miniforge.sh
fi

eval "$("$MINIFORGE_DIR/bin/conda" shell.bash hook)"

# --- Bioconda channel ---
echo "Configuring channels (conda-forge + bioconda)..."
conda config --add channels bioconda 2>/dev/null || true
conda config --add channels conda-forge 2>/dev/null || true
conda config --set channel_priority strict

# --- Biotools environment ---
ENV_NAME="${BIOTOOLS_ENV:-biotools}"

if conda env list | grep -q "^${ENV_NAME} "; then
  echo "Conda environment '$ENV_NAME' already exists"
else
  echo "Creating conda environment '$ENV_NAME'..."
  conda create -y -q -n "$ENV_NAME" mafft muscle mash
fi

echo ""
echo "=== Bioinformatics tools ready ==="
echo "  Activate:       eval \"\$(~/miniforge3/bin/conda shell.bash hook)\" && conda activate $ENV_NAME"
echo "  Align (MAFFT):  mafft --auto input.fasta > aligned.fasta"
echo "  Align (MUSCLE):  muscle -align input.fasta -output aligned.fasta"
