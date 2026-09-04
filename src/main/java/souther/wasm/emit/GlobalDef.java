/*
 * Copied from rontolisp (https://github.com/making/rontolisp), Copyright Toshiaki Maki,
 * licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 and NOTICE.
 *
 * Changed from the original: the package name am.ik.wasm was rewritten to souther.wasm.emit,
 * wherever it appears. Nothing else was changed.
 */
package souther.wasm.emit;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

/**
 * Definition for WASM global section entries.
 */
public class GlobalDef extends CountingDef<GlobalDef> {

	/** Creates a new empty global definition. */
	public GlobalDef() {
	}

	/**
	 * Add a global variable entry.
	 * @param type the value type
	 * @param mutability the mutability
	 * @param consumer a consumer that writes the init expression
	 * @return this instance for chaining
	 */
	public GlobalDef addGlobal(Type type, Mutability mutability, Consumer<WasmWriter> consumer) {
		final ByteArrayOutputStream stream = new ByteArrayOutputStream();
		consumer.accept(new WasmWriter(stream));
		return this.add(global -> global.write(type, mutability, stream.toByteArray(), Instruction.END));
	}

}
