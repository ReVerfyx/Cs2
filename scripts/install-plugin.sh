#!/usr/bin/env bash
set -euo pipefail

CS2_ROOT="${1:-}"

if [[ -z "$CS2_ROOT" ]]; then
  echo "Usage: $0 /path/to/cs2-server"
  exit 1
fi

CSGO_DIR="$CS2_ROOT/game/csgo"
CSS_DIR="$CSGO_DIR/addons/counterstrikesharp"
PLUGIN_DIR="$CSS_DIR/plugins/Sol"

if [[ ! -d "$CSGO_DIR" ]]; then
  echo "ERROR: $CSGO_DIR not found"
  exit 2
fi

if [[ ! -d "$CSS_DIR" ]]; then
  echo "ERROR: CounterStrikeSharp not found in $CSS_DIR"
  echo "Install Metamod:Source 2.x and CounterStrikeSharp with-runtime first."
  exit 3
fi

if ! command -v dotnet >/dev/null 2>&1; then
  echo "ERROR: dotnet SDK 10 is required to build the plugin."
  exit 4
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$ROOT_DIR/build/Sol"

rm -rf "$BUILD_DIR"
dotnet publish "$ROOT_DIR/src/Sol/Sol.csproj" -c Release -o "$BUILD_DIR"

rm -rf "$PLUGIN_DIR"
mkdir -p "$PLUGIN_DIR"
cp -a "$BUILD_DIR"/. "$PLUGIN_DIR"/

mkdir -p "$CSGO_DIR/cfg"
cp "$ROOT_DIR/cfg/sol_server.cfg" "$CSGO_DIR/cfg/sol_server.cfg"

echo
echo "Installed:"
echo "  plugin -> $PLUGIN_DIR"
echo "  cfg    -> $CSGO_DIR/cfg/sol_server.cfg"
echo
echo "Restart CS2, then check:"
echo "  meta list"
echo "  css_plugins list"
echo
echo "Optional in server console:"
echo "  exec sol_server.cfg"
