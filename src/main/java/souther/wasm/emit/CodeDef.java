/*
 * Copied from rontolisp (https://github.com/making/rontolisp), Copyright Toshiaki Maki,
 * licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 and NOTICE.
 *
 * Changed from the original: the package name am.ik.wasm was rewritten to souther.wasm.emit,
 * wherever it appears. Nothing else was changed.
 */
package souther.wasm.emit;

/**
 * Definition for WASM code section entries.
 */
public class CodeDef extends CountingDef<CodeDef> {

	/** Creates a new empty code definition. */
	public CodeDef() {
	}

	/**
	 * Add a function body to the code section.
	 * @param body the function body bytes
	 * @return this instance for chaining
	 */
	public CodeDef addFunction(byte[] body) {
		// The size prefix is a u32: a SIGNED LEB pads any length in [64, 127] (and every
		// 64th block above it) with a redundant continuation byte.
		return this.add(function -> function.writeUnsignedLeb128(body.length).write(body));
	}

}
