/*
 * Copied from rontolisp (https://github.com/making/rontolisp), Copyright Toshiaki Maki,
 * licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 and NOTICE.
 *
 * Changed from the original: the package name am.ik.wasm was rewritten to souther.wasm.emit,
 * wherever it appears. Nothing else was changed.
 */
package souther.wasm.emit;

/**
 * Definition for WASM memory section entries.
 */
public class MemoryDef extends CountingDef<MemoryDef> {

	/** Creates a new empty memory definition. */
	public MemoryDef() {
	}

	/**
	 * Add a memory with a minimum size only.
	 * @param initial the minimum number of pages
	 * @return this instance for chaining
	 */
	public MemoryDef addMemory(int initial) {
		return this.add(memory -> memory.write(Limit.MIN, initial));
	}

	/**
	 * Add a memory with minimum and maximum sizes.
	 * @param initial the minimum number of pages
	 * @param maximum the maximum number of pages
	 * @return this instance for chaining
	 */
	public MemoryDef addMemory(int initial, int maximum) {
		return this.add(memory -> memory.write(Limit.MINMAX, initial, maximum));
	}

}
