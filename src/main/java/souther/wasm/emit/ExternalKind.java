/*
 * Copied from rontolisp (https://github.com/making/rontolisp), Copyright Toshiaki Maki,
 * licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 and NOTICE.
 *
 * Changed from the original: the package name am.ik.wasm was rewritten to souther.wasm.emit,
 * wherever it appears. Nothing else was changed.
 */
package souther.wasm.emit;

/**
 * WASM external kind values for import and export entries.
 */
public enum ExternalKind implements Codable {

	/** Function external kind (0). */
	FUNCTION(0), //
	/** Table external kind (1). */
	TABLE(1), //
	/** Memory external kind (2). */
	MEMORY(2), //
	/** Global external kind (3). */
	GLOBAL(3) //
	;

	private final int code;

	ExternalKind(int code) {
		this.code = code;
	}

	@Override
	public int code() {
		return code;
	}

}
