#!/bin/sh
# Builds the runtime the way CI builds it, and carries the result into src/main/resources.
#
# What the runtime compiles to depends on the machine it is compiled on, not only on its source:
# a dependency with a build script (num-traits, under num-bigint) is hashed by Cargo together with
# the host's triple, the hash is in every symbol that depends on it, and the order the linker lays
# functions out in follows the symbols. So the same commit builds one module on a Mac and another
# on Linux, and CI, which requires the carried module to be what the source builds, builds on
# x86-64 Linux. This builds there too, in the Rust image of the toolchain rust-toolchain.toml
# names, whatever machine runs it.
#
#     runtime/build.sh

set -eu

repository=$(cd "$(dirname "$0")/.." && pwd)
toolchain=$(sed -n 's/^channel = "\(.*\)"$/\1/p' "$repository/rust-toolchain.toml")
test -n "$toolchain"

docker run --rm --platform linux/amd64 \
    --volume "$repository:/repository" \
    --volume souther-wasm-compiler-cargo-registry:/usr/local/cargo/registry \
    --workdir /repository/runtime \
    --env CARGO_TARGET_DIR=/repository/runtime/target/x86_64-linux \
    "rust:$toolchain" \
    sh -c 'rustup target add wasm32-unknown-unknown >/dev/null && cargo build --release --target wasm32-unknown-unknown'

cp "$repository/runtime/target/x86_64-linux/wasm32-unknown-unknown/release/souther_wasm_runtime.wasm" \
    "$repository/src/main/resources/souther/wasm/runtime.wasm"
echo "carried the runtime built for x86-64 Linux into src/main/resources/souther/wasm/runtime.wasm"
