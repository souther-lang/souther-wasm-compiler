/*
 * Copied from rontolisp (https://github.com/making/rontolisp), Copyright Toshiaki Maki,
 * licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 and NOTICE.
 *
 * Changed from the original: the package name am.ik.wasm was rewritten to souther.wasm.emit,
 * wherever it appears. Nothing else was changed.
 */
package souther.wasm.emit;

/**
 * Definition for WASM function section entries.
 */
public class FunctionDef extends CountingDef<FunctionDef> {

	/** Creates a new empty function definition. */
	public FunctionDef() {
	}

	/**
	 * Add a function entry referencing a type signature.
	 * @param signatureIndex the index of the function type signature
	 * @return this instance for chaining
	 */
	public FunctionDef addFunction(int signatureIndex) {
		// The type index is a WASM u32 LEB128 field; a raw byte silently truncates
		// once a module needs 128 or more distinct function signatures, which
		// produces a section whose declared size is one byte short of its own
		// contents -- an invalid module that only shows up downstream.
		return this.add(function -> function.writeUnsignedLeb128(signatureIndex));
	}

}
