#!/usr/bin/env bash
# Reinstall Volt's development dependencies on this machine.
#
# Why this script exists: this network cannot reach registry.npmjs.org or
# electronjs.org/headers (the Electron header CDN). It uses mirrors that work
# here, and builds native modules against Node 22 headers. The native modules
# in this tree are N-API, so they also load correctly in Electron 37.
#
# Run `make start` for normal day-to-day development. Only run this script when
# you change dependencies or need to repair node_modules/.build.
set -euo pipefail

cd "$(dirname "$0")"
NODE_VERSION="$(cat .nvmrc)"

export NVM_DIR="${NVM_DIR:-$HOME/.nvm}"
# shellcheck disable=SC1091
. "$NVM_DIR/nvm.sh"

if ! nvm use "$NODE_VERSION" >/dev/null 2>&1; then
	echo "Node $NODE_VERSION is not installed in nvm." >&2
	echo "Install it with: nvm install $NODE_VERSION" >&2
	exit 1
fi

export npm_config_registry=https://registry.npmmirror.com
export npm_config_runtime=node
export npm_config_target="$NODE_VERSION"
export npm_config_disturl=https://mirrors.tuna.tsinghua.edu.cn/nodejs-release
export npm_config_build_from_source=true
export npm_config_fetch_retries=10
export npm_config_fetch_retry_mintimeout=20000
export npm_config_fetch_retry_maxtimeout=180000
export npm_config_fetch_timeout=900000
export npm_config_prefer_offline=true
export ELECTRON_SKIP_BINARY_DOWNLOAD=1

# Keep nested extension installs sequential enough to avoid mirror timeouts.
export VSCODE_NPM_INSTALL_CONCURRENCY=2

echo "==> npm install"
npm install

echo "==> Electron runtime"
ELECTRON_MIRROR=https://registry.npmmirror.com/-/binary/electron/ \
ELECTRON_CUSTOM_DIR=37.6.0 \
	npm run electron

echo "==> Built-in extensions"
node build/lib/builtInExtensions.js

echo
echo "Done. Start Volt with: make start"
